package com.ai.teachingassistant.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Arrays;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
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

    private final WebClient ollamaWebClient;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;
    private final StreamCancellationService cancellationService;
    private final PythonRagClient pythonRagClient;

    @Value("${ollama.model:llama3.2:latest}")
    private String ollamaModel;

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

            // ── 5. Stream answer from Ollama ──────────────────────────────────
            String fullAnswer = streamFromOllama(lectureId, prompt, 500, CTX_QA);

            if (fullAnswer == null || fullAnswer.isBlank()) {
                sendError(lectureId, "Ollama returned an empty response.");
                return CompletableFuture.completedFuture(null);
            }

            log.info("Streaming Q&A complete for lectureId={}, elapsed={}ms",
                    lectureId, System.currentTimeMillis() - startTime);

            // ── 6. Send completion event with source chunks ───────────────────
            messagingTemplate.convertAndSend(
                    TOPIC_PREFIX + lectureId,
                    QaStreamMessage.completed(lectureId, question, fullAnswer, chunks, chunks.size()));

        } catch (Exception e) {
            log.error("Streaming Q&A FAILED for lectureId={}: {}", lectureId, e.getMessage(), e);
            sendError(lectureId, "Q&A failed: " + e.getMessage());
        }

        return CompletableFuture.completedFuture(null);
    }

    // ── Ollama streaming — identical pattern to StreamingSummarizationService ─

    /**
     * Streams a prompt to Ollama and pushes each token to the Q&A WebSocket topic.
     * Returns the full accumulated response text.
     *
     * <p>
     * This method is a direct copy of
     * {@code StreamingSummarizationService#streamFromOllama} with the only
     * difference being the STOMP topic suffix ({@code /qa}) and the
     * {@link QaStreamMessage} DTO instead of
     * {@link com.ai.teachingassistant.dto.SummaryStreamMessage}.
     */
    private String streamFromOllama(String lectureId, String prompt, int maxTokens, int numCtx) {
        StringBuilder fullAnswer = new StringBuilder();
        AtomicReference<Throwable> streamError = new AtomicReference<>();

        // ── Ollama generation options (same tuning as summarization) ─────────
        Map<String, Object> options = new java.util.HashMap<>();
        options.put("num_ctx", numCtx);
        options.put("temperature", 0.3);
        options.put("repeat_penalty", 1.1);

        Map<String, Object> requestBody = new java.util.HashMap<>();
        requestBody.put("model", ollamaModel);
        requestBody.put("prompt", prompt);
        requestBody.put("stream", true);
        requestBody.put("num_predict", maxTokens);
        requestBody.put("options", options);

        Flux<String> chunkFlux = ollamaWebClient.post()
                .uri("/api/generate")
                .header("Accept", "application/x-ndjson")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToFlux(DataBuffer.class) // raw bytes, no buffering
                .map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer); // prevent memory leak
                    return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                })
                .flatMapIterable(response -> Arrays.asList(response.split("\n")))
                .filter(line -> !line.isBlank());

        chunkFlux
                // Stop cleanly when the user clicks "Stop"
                .takeWhile(line -> !cancellationService.isCancelled(lectureId))
                .doOnNext(line -> {
                    try {
                        JsonNode node = objectMapper.readTree(line);
                        String token = node.path("response").asText("");

                        if (!token.isEmpty()) {
                            fullAnswer.append(token);

                            // Push every token to the WebSocket topic immediately
                            messagingTemplate.convertAndSend(
                                    TOPIC_PREFIX + lectureId,
                                    QaStreamMessage.chunk(lectureId, token));
                        }

                        if (node.path("done").asBoolean(false)) {
                            log.debug("Ollama signaled done=true for lectureId={}", lectureId);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to parse Ollama QA chunk for lectureId={}: {}",
                                lectureId, e.getMessage());
                    }
                })
                .doOnError(error -> {
                    log.error("Stream error for lectureId={}: {}",
                            lectureId, error.getMessage(), error);
                    streamError.set(error);
                })
                .blockLast(); // safe — we are on a dedicated @Async thread

        // Append stop notice if the user cancelled mid-stream
        if (cancellationService.isCancelled(lectureId)) {
            log.info("Ollama Q&A streaming CANCELLED by user for lectureId={}", lectureId);
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