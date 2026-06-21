package com.ai.teachingassistant.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.ai.teachingassistant.client.PythonRagClient;
import com.ai.teachingassistant.dto.QaStreamMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * StreamingQaService — mirrors StreamingSummarizationService exactly.
 *
 * <p>
 * Flow:
 * <ol>
 * <li>Retrieve relevant context chunks from Python RAG service.</li>
 * <li>Build a grounded prompt from the retrieved chunks.</li>
 * <li>Stream Ollama response token-by-token over WebSocket (STOMP)
 * to {@code /topic/lectures/{lectureId}/qa}.</li>
 * <li>Send {@code ANSWER_COMPLETED} with full answer + source chunks when
 * done.</li>
 * </ol>
 *
 * <h3>WebSocket message types on {@code /topic/lectures/{lectureId}/qa}</h3>
 * 
 * <pre>
 * ANSWER_CHUNK     → chunk   (one streaming token)
 * ANSWER_COMPLETED → fullAnswer, sourceChunks, chunksUsed
 * ANSWER_ERROR     → error   (human-readable message)
 * </pre>
 *
 * <h3>Architecture (mirrors StreamingSummarizationService)</h3>
 * 
 * <pre>
 *  ┌───────────┐  POST /api/lecture/{id}/ask-stream   ┌──────────────────────┐
 *  │  React UI │ ───────────────────────────────────► │   QaController       │
 *  │           │  ◄── 202 ACCEPTED (instantly)        │   (HTTP thread freed)│
 *  └─────┬─────┘                                     └──────────┬────────────┘
 *        │                                                      │
 *        │  WebSocket /topic/lectures/{id}/qa                   │ @Async
 *        │  ◄──────────────────────────────────────┐            ▼
 *        │                                         │  ┌──────────────────────────┐
 *        │  ANSWER_CHUNK  ◄────────────────────────┤  │  StreamingQaService      │
 *        │  ANSWER_CHUNK  ◄────────────────────────┤  │                          │
 *        │  ANSWER_COMPLETED ◄─────────────────────┤  │  Python RAG → chunks     │
 *        │                                         │  │  WebClient → Ollama      │
 *        │                                         │  │  stream=true (Flux)      │
 *        │                                         └──│  SimpMessagingTemplate   │
 *        │                                            └──────────────────────────┘
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StreamingQaService {

    private final WebClient groqWebClient;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;
    private final StreamCancellationService cancellationService;
    private final PythonRagClient pythonRagClient;

    @Value("${groq.model:llama-3.3-70b-versatile}")
    private String groqModel;

    /** STOMP destination prefix — matches the topic the frontend subscribes to. */
    private static final String TOPIC_PREFIX = "/topic/qa/";

    /**
     * Context window for the Q&A prompt.
     * Larger than summary chunks because the prompt contains both retrieved
     * context AND the user question.
     */
    private static final int CTX_QA = 4096;

    // ── Entry point ───────────────────────────────────────────────────────────

    /**
     * Runs on a dedicated async thread (same executor as summary streaming).
     * Returns immediately to the caller — results arrive via WebSocket.
     *
     * @param lectureId the lecture UUID
     * @param question  the student's question
     */
    @Async("summarizationExecutor")
    public CompletableFuture<Void> streamAnswer(String lectureId, String question) {
        log.info("Streaming Q&A starting for lectureId={}, question='{}'", lectureId, question);
        long startTime = System.currentTimeMillis();

        // Clear any previous cancellation for this lecture
        cancellationService.clearCancellation(lectureId);

        try {
            // ── 1. Notify the user that context retrieval is in progress ──────
            sendChunk(lectureId, "*Searching for relevant context…*\n\n");

            // ── 2. Retrieve relevant chunks from Python RAG ───────────────────
            PythonRagClient.RetrieveContextResponse contextResponse = null;
            try {
                contextResponse = pythonRagClient.retrieveContext(question, lectureId).block();
            } catch (Exception e) {
                log.error("Failed to retrieve RAG context for lectureId={}: {}", lectureId, e.getMessage());
            }

            // ── 2b. Check Cache First ─────────────────────────────────────────
            if (contextResponse != null && contextResponse.getCachedAnswer() != null && !contextResponse.getCachedAnswer().isBlank()) {
                sendChunk(lectureId, "*⚡ Served from FAQ Cache*\n\n");
                sendChunk(lectureId, contextResponse.getCachedAnswer());
                messagingTemplate.convertAndSend(
                        TOPIC_PREFIX + lectureId,
                        QaStreamMessage.completed(lectureId, question, contextResponse.getCachedAnswer(), List.of(), 0));
                log.info("Returning cached answer for lectureId={}", lectureId);
                return CompletableFuture.completedFuture(null);
            }

            List<String> chunks = (contextResponse != null && contextResponse.getChunks() != null)
                    ? contextResponse.getChunks()
                    : List.of();

            // ── 3. Handle case where no relevant content was found ────────────
            if (chunks.isEmpty()) {
                String fallback = "I couldn't find relevant information in the lecture material "
                        + "to answer your question. Please try rephrasing or ask about a topic "
                        + "covered in the uploaded lecture.";
                sendChunk(lectureId, fallback);
                messagingTemplate.convertAndSend(
                        TOPIC_PREFIX + lectureId,
                        QaStreamMessage.completed(lectureId, question, fallback, chunks, 0));
                return CompletableFuture.completedFuture(null);
            }

            sendChunk(lectureId, "*Found " + chunks.size() + " relevant section"
                    + (chunks.size() == 1 ? "" : "s") + ". Generating answer…*\n\n");

            // ── 4. Build grounded prompt ──────────────────────────────────────
            List<String> limitedChunks = chunks.stream().limit(3).toList();
            String contextText = String.join("\n\n---\n\n", limitedChunks);
            String prompt = buildQaPrompt(contextText, question);

            // ── 5. Stream answer from Groq ──────────────────────────────────
            String fullAnswer = streamFromGroq(lectureId, prompt, 500, CTX_QA);

            if (fullAnswer == null || fullAnswer.isBlank()) {
                sendError(lectureId, "Groq returned an empty response.");
                return CompletableFuture.completedFuture(null);
            }

            log.info("Streaming Q&A complete for lectureId={}, elapsed={}ms",
                    lectureId, System.currentTimeMillis() - startTime);

            // ── 6. Send completion event with source chunks ───────────────────
            messagingTemplate.convertAndSend(
                    TOPIC_PREFIX + lectureId,
                    QaStreamMessage.completed(lectureId, question, fullAnswer, chunks, chunks.size()));

            // ── 7. Save to Cache ─────────────────────────────────────────────
            try {
                pythonRagClient.saveCache(lectureId, question, fullAnswer).subscribe();
            } catch (Exception e) {
                log.error("Failed to save QA to cache: {}", e.getMessage());
            }

        } catch (Exception e) {
            log.error("Streaming Q&A FAILED for lectureId={}: {}", lectureId, e.getMessage(), e);
            sendError(lectureId, "Q&A failed: " + e.getMessage());
        }

        return CompletableFuture.completedFuture(null);
    }

    // ── Groq streaming — identical pattern to StreamingSummarizationService ─

    /**
     * Streams a prompt to Groq and pushes each token to the Q&A WebSocket topic.
     * Returns the full accumulated response text.
     *
     * <p>
     * Logs two timing metrics per request:
     * <ul>
     * <li><b>FIRST TOKEN latency</b> — time from request dispatch to the first
     * non-empty token; reflects model warm-up + prompt-processing overhead.</li>
     * <li><b>TOTAL generation time</b> — wall-clock time for the entire stream;
     * useful for throughput analysis.</li>
     * </ul>
     */
    private String streamFromGroq(String lectureId, String prompt, int maxTokens, int numCtx) {
        StringBuilder fullAnswer = new StringBuilder();
        AtomicReference<Throwable> streamError = new AtomicReference<>();

        // ── Groq timing metrics ─────────────────────────────────────────────
        long groqStart = System.currentTimeMillis();
        AtomicBoolean firstTokenLogged = new AtomicBoolean(false);

        Map<String, Object> requestBody = new java.util.HashMap<>();
        requestBody.put("model", groqModel);
        requestBody.put("stream", true);
        requestBody.put("max_tokens", maxTokens);
        List<Map<String, String>> messages = new java.util.ArrayList<>();
        messages.add(Map.of("role", "user", "content", prompt));
        requestBody.put("messages", messages);

        Flux<org.springframework.http.codec.ServerSentEvent<String>> chunkFlux = groqWebClient.post()
                .uri("/chat/completions")
                .accept(org.springframework.http.MediaType.TEXT_EVENT_STREAM)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToFlux(new org.springframework.core.ParameterizedTypeReference<org.springframework.http.codec.ServerSentEvent<String>>() {});

        chunkFlux
                // Stop cleanly when the user clicks "Stop"
                .takeWhile(event -> !cancellationService.isCancelled(lectureId))
                .doOnNext(event -> {
                    try {
                        String data = event.data();
                        if (data != null) {
                            if (data.equals("[DONE]")) {
                                log.debug("Groq signaled done for lectureId={}", lectureId);
                                return;
                            }
                            JsonNode node = objectMapper.readTree(data);
                            JsonNode choices = node.path("choices");
                            if (choices.isArray() && choices.size() > 0) {
                                JsonNode delta = choices.get(0).path("delta");
                                if (delta.has("content")) {
                                    String token = delta.path("content").asText("");
                                    if (!token.isEmpty()) {
                                        // ── First-token latency ───────────────────────────
                                        if (!firstTokenLogged.get()) {
                                            long firstTokenTime = System.currentTimeMillis() - groqStart;
                                            log.info("🔥 Groq FIRST TOKEN latency = {} ms (lectureId={})",
                                                    firstTokenTime, lectureId);
                                            firstTokenLogged.set(true);
                                        }

                                        fullAnswer.append(token);

                                        // Push every token to the WebSocket topic immediately
                                        messagingTemplate.convertAndSend(
                                                TOPIC_PREFIX + lectureId,
                                                QaStreamMessage.chunk(lectureId, token));
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Failed to parse Groq QA chunk for lectureId={}: {}",
                                lectureId, e.getMessage());
                    }
                })
                .doOnError(error -> {
                    log.error("Stream error for lectureId={}: {}",
                            lectureId, error.getMessage(), error);
                    streamError.set(error);
                })
                // ── Total generation time ─────────────────────────────────────
                .doOnComplete(() -> {
                    long totalTime = System.currentTimeMillis() - groqStart;
                    log.info("✅ Groq TOTAL generation time = {} ms (lectureId={})",
                            totalTime, lectureId);
                })
                .blockLast(); // safe — we are on a dedicated @Async thread

        // Append stop notice if the user cancelled mid-stream
        if (cancellationService.isCancelled(lectureId)) {
            log.info("Groq Q&A streaming CANCELLED by user for lectureId={}", lectureId);
            sendChunk(lectureId, "\n\n*[Generation stopped by user]*");
        }

        if (streamError.get() != null) {
            sendError(lectureId, "Streaming failed: " + streamError.get().getMessage());
            return null;
        }

        return fullAnswer.toString();
    }

    // ── Prompt ────────────────────────────────────────────────────────────────

    /**
     * Builds a RAG-grounded Q&A prompt.
     * The model is instructed to answer only from the provided context,
     * which prevents hallucination.
     */
    private String buildQaPrompt(String contextText, String question) {
        return """
                You are an expert teaching assistant helping a student understand their lecture material.

                Use ONLY the context below to answer the question.
                Be specific, clear, and educational in your explanation.
                If the answer is not in the context, say exactly:
                "This topic isn't covered in the provided lecture material."
                Do NOT make up facts. Do NOT use outside knowledge.

                --- LECTURE CONTEXT ---
                %s
                --- END CONTEXT ---

                STUDENT QUESTION:
                %s

                ANSWER (be thorough — explain concepts clearly, use examples from the context):
                """.formatted(contextText, question);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Pushes a single text chunk to the Q&A WebSocket topic. */
    private void sendChunk(String lectureId, String text) {
        messagingTemplate.convertAndSend(
                TOPIC_PREFIX + lectureId,
                QaStreamMessage.chunk(lectureId, text));
    }

    /** Pushes an error message to the Q&A WebSocket topic. */
    private void sendError(String lectureId, String errorMessage) {
        messagingTemplate.convertAndSend(
                TOPIC_PREFIX + lectureId,
                QaStreamMessage.error(lectureId, errorMessage));
    }
}