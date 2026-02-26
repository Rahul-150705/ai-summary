package com.ai.teachingassistant.service;

import com.ai.teachingassistant.dto.SummaryStreamMessage;
import com.ai.teachingassistant.model.Lecture;
import com.ai.teachingassistant.repository.LectureRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * StreamingSummarizationService — streams Ollama LLM output chunk-by-chunk
 * over WebSocket (STOMP) so the React frontend can display a live typing
 * effect.
 *
 * <h3>Architecture flow</h3>
 * 
 * <pre>
 *  ┌───────────┐  POST /api/lecture/{id}/summarize    ┌──────────────────────┐
 *  │  React UI │ ────────────────────────────────────► │  LectureController   │
 *  │           │  ◄── 202 ACCEPTED (returns instantly) │  (HTTP thread freed) │
 *  └─────┬─────┘                                      └──────────┬───────────┘
 *        │                                                       │
 *        │  WebSocket /topic/lectures/{id}                       │ @Async
 *        │  ◄───────────────────────────────────────┐            ▼
 *        │                                          │  ┌────────────────────────────┐
 *        │  SUMMARY_CHUNK  ◄────────────────────────┤  │ StreamingSummarizationSvc  │
 *        │  SUMMARY_CHUNK  ◄────────────────────────┤  │                            │
 *        │  SUMMARY_CHUNK  ◄────────────────────────┤  │  WebClient → Ollama        │
 *        │  SUMMARY_COMPLETED ◄─────────────────────┤  │  stream=true (Flux)        │
 *        │                                          │  │  SimpMessagingTemplate     │
 *        │                                          │  │  → /topic/lectures/{id}    │
 *        │                                          └──│                            │
 *        │                                             │  On complete: save to DB   │
 *        │                                             └────────────────────────────┘
 * </pre>
 *
 * <h3>Why async processing is required</h3>
 * <ul>
 * <li>Ollama streaming can take 30s–5min depending on model size and prompt
 * length.</li>
 * <li>If we ran this on the HTTP request thread, the client would hang and
 * eventually
 * time out. The thread would also be blocked, reducing server capacity.</li>
 * <li>By marking this method {@code @Async}, Spring dispatches it to a
 * background
 * thread pool ({@code summarizationExecutor}), freeing the HTTP thread
 * instantly.</li>
 * </ul>
 *
 * <h3>How this scales for multiple concurrent users</h3>
 * <ol>
 * <li><b>WebClient is non-blocking:</b> Each streaming session uses an
 * event-loop
 * thread (Netty), not a platform thread. Hundreds of concurrent streams share
 * a small pool of Netty threads.</li>
 * <li><b>STOMP topic isolation:</b> Each lecture has its own topic
 * ({@code /topic/lectures/{lectureId}}), so subscribers only receive their own
 * chunks — no cross-talk between users.</li>
 * <li><b>Thread pool for @Async:</b> The {@code summarizationExecutor} has a
 * bounded
 * pool (core=2, max=4, queue=20). If 20+ concurrent summarizations are
 * requested,
 * the queue acts as backpressure. In production, tune these values up and
 * consider an external message broker (RabbitMQ) for horizontal scaling.</li>
 * <li><b>Thread-safe accumulation:</b> Each streaming session gets its own
 * {@code StringBuilder} on its own @Async thread — no shared mutable
 * state.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StreamingSummarizationService {

    private final WebClient ollamaWebClient;
    private final SimpMessagingTemplate messagingTemplate;
    private final LectureRepository lectureRepository;
    private final ObjectMapper objectMapper;

    @Value("${ollama.model:llama3.2}")
    private String ollamaModel;

    /** STOMP destination prefix for streaming summary messages. */
    private static final String TOPIC_PREFIX = "/topic/lectures/";

    /**
     * Kicks off streaming summarization on a background thread.
     *
     * <p>
     * This method:
     * <ol>
     * <li>Loads the lecture's extracted text from the DB.</li>
     * <li>Calls Ollama with {@code stream: true} via WebClient.</li>
     * <li>For each NDJSON chunk, extracts the "response" field and pushes it
     * as a {@code SUMMARY_CHUNK} message to the WebSocket topic.</li>
     * <li>Accumulates all chunks into a {@code StringBuilder} (thread-local,
     * so thread-safe).</li>
     * <li>On stream completion, saves the full summary to MySQL and sends a
     * {@code SUMMARY_COMPLETED} message.</li>
     * <li>On error, sends a {@code SUMMARY_ERROR} message.</li>
     * </ol>
     *
     * @param lectureId the UUID of the lecture to summarize
     */
    @Async("summarizationExecutor")
    public CompletableFuture<Void> streamSummarization(String lectureId) {
        log.info("Streaming summarization starting for lectureId={}", lectureId);
        long startTime = System.currentTimeMillis();

        try {
            // ── 1. Load lecture text from DB ─────────────────────────────────
            Lecture lecture = lectureRepository.findById(lectureId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Lecture not found: " + lectureId));

            String extractedText = lecture.getOriginalText();
            if (extractedText == null || extractedText.isBlank()) {
                sendError(lectureId, "No extracted text available for this lecture.");
                return CompletableFuture.completedFuture(null);
            }

            String prompt = buildPrompt(extractedText);

            // ── 2. Call Ollama with stream=true ──────────────────────────────
            // Thread-safe: each @Async invocation gets its own StringBuilder instance
            StringBuilder fullSummary = new StringBuilder();

            // AtomicReference to capture errors from the reactive pipeline
            AtomicReference<Throwable> streamError = new AtomicReference<>();

            // Build the request body for Ollama's /api/generate
            Map<String, Object> requestBody = Map.of(
                    "model", ollamaModel,
                    "prompt", prompt,
                    "stream", true,
                    "num_predict", 3000);

            // ── 3. Stream chunks via WebClient ───────────────────────────────
            // WebClient.post() returns a Flux<String> where each element is one
            // NDJSON line from Ollama. We parse each line, extract the "response"
            // token, and push it to the WebSocket topic.
            Flux<String> chunkFlux = ollamaWebClient.post()
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToFlux(String.class);

            // Block on the @Async thread (this thread IS dedicated to this task)
            // so we can process chunks synchronously and guarantee ordering.
            chunkFlux
                    .doOnNext(line -> {
                        try {
                            JsonNode node = objectMapper.readTree(line);
                            String token = node.path("response").asText("");

                            if (!token.isEmpty()) {
                                // Append to the running summary
                                fullSummary.append(token);

                                // Push chunk to WebSocket subscribers
                                messagingTemplate.convertAndSend(
                                        TOPIC_PREFIX + lectureId,
                                        SummaryStreamMessage.chunk(lectureId, token));
                            }

                            // Check if Ollama signals completion
                            if (node.path("done").asBoolean(false)) {
                                log.debug("Ollama signaled done=true for lectureId={}",
                                        lectureId);
                            }
                        } catch (Exception e) {
                            log.warn("Failed to parse Ollama chunk for lectureId={}: {}",
                                    lectureId, e.getMessage());
                        }
                    })
                    .doOnError(error -> {
                        log.error("Stream error for lectureId={}: {}",
                                lectureId, error.getMessage(), error);
                        streamError.set(error);
                    })
                    .blockLast(); // safe to block — we're on a dedicated @Async thread

            // ── 4. Check for errors ──────────────────────────────────────────
            if (streamError.get() != null) {
                sendError(lectureId, "Streaming failed: " + streamError.get().getMessage());
                return CompletableFuture.completedFuture(null);
            }

            // ── 5. Save full summary to MySQL ────────────────────────────────
            String completeSummary = fullSummary.toString();

            if (completeSummary.isBlank()) {
                sendError(lectureId, "Ollama returned an empty response.");
                return CompletableFuture.completedFuture(null);
            }

            lecture.setSummary(completeSummary);
            lecture.setProvider("ollama");
            lectureRepository.save(lecture);

            log.info("Streaming summarization complete for lectureId={}, " +
                    "length={} chars, elapsed={}ms",
                    lectureId, completeSummary.length(),
                    System.currentTimeMillis() - startTime);

            // ── 6. Send completion event ─────────────────────────────────────
            messagingTemplate.convertAndSend(
                    TOPIC_PREFIX + lectureId,
                    SummaryStreamMessage.completed(lectureId, completeSummary));

        } catch (Exception e) {
            log.error("Streaming summarization FAILED for lectureId={}: {}",
                    lectureId, e.getMessage(), e);
            sendError(lectureId, "Summarization failed: " + e.getMessage());
        }

        return CompletableFuture.completedFuture(null);
    }

    /**
     * Sends an error message over the WebSocket topic for the given lecture.
     */
    private void sendError(String lectureId, String errorMessage) {
        messagingTemplate.convertAndSend(
                TOPIC_PREFIX + lectureId,
                SummaryStreamMessage.error(lectureId, errorMessage));
    }

    /**
     * Constructs the summarization prompt.
     * Reuses the same structured prompt style as SummarizationService
     * so the output format is familiar.
     */
    private String buildPrompt(String lectureText) {
        String truncated = truncateText(lectureText, 12000);
        return """
                You are an expert university-level teaching assistant.
                Read the lecture content below and produce a detailed, well-structured summary.

                Use EXACTLY these section markers on their own line. Start each section on a new line.
                Write in full sentences. Do not skip any section.

                [TITLE]
                Write a short, descriptive title for this lecture.

                [OVERVIEW]
                Write 4-5 sentences summarising what this lecture is about, its main goals and key arguments.

                [KEY_CONCEPTS]
                List at least 8 key concepts as bullet points starting with "- ". Each bullet must be a full sentence.

                [DEFINITIONS]
                List at least 6 important terms as bullet points starting with "- Term: definition".

                [DETAILED_EXPLANATION]
                Write 3 to 5 paragraphs (separated by blank lines) that deeply explain the most important ideas, with examples.

                [EXAM_POINTS]
                List at least 8 exam-focused takeaways as bullet points starting with "- ".

                [FURTHER_READING]
                List 2-3 recommended resources (books, websites, or topics) as bullet points starting with "- ".

                --- LECTURE CONTENT ---
                %s
                --- END ---
                """
                .formatted(truncated);
    }

    /** Truncates text to respect LLM token limits. */
    private String truncateText(String text, int maxChars) {
        if (text.length() <= maxChars)
            return text;
        log.warn("Lecture text truncated from {} to {} characters.", text.length(), maxChars);
        return text.substring(0, maxChars) + "\n\n[... content truncated due to length ...]";
    }
}
