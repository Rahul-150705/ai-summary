package com.ai.teachingassistant.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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

    private static final String TOPIC_PREFIX = "/topic/lectures/";

    @Async("summarizationExecutor")
    public CompletableFuture<Void> streamAnswer(String lectureId, String question) {
        log.info("Streaming Q&A starting for lectureId={}, question='{}'", lectureId, question);
        cancellationService.clearCancellation(lectureId);

        try {
            // Send initial chunk
            messagingTemplate.convertAndSend(
                    TOPIC_PREFIX + lectureId + "/qa",
                    QaStreamMessage.chunk(lectureId, "*Searching for relevant context...*\n\n"));

            // 1. Get Context from Python (Blocking inside the async thread)
            PythonRagClient.RetrieveContextResponse contextResponse = null;
            try {
                contextResponse = pythonRagClient.retrieveContext(question, lectureId).block();
            } catch (Exception e) {
                log.error("Failed to retrieve context from Python: {}", e.getMessage());
            }

            List<String> chunks = (contextResponse != null && contextResponse.getChunks() != null) 
                    ? contextResponse.getChunks() 
                    : List.of();

            if (chunks.isEmpty()) {
                String fallback = "I couldn't find relevant information in the lecture material to answer your question.";
                messagingTemplate.convertAndSend(
                        TOPIC_PREFIX + lectureId + "/qa",
                        QaStreamMessage.chunk(lectureId, fallback));
                messagingTemplate.convertAndSend(
                        TOPIC_PREFIX + lectureId + "/qa",
                        QaStreamMessage.completed(lectureId, question, fallback, chunks, 0));
                return CompletableFuture.completedFuture(null);
            }

            messagingTemplate.convertAndSend(
                    TOPIC_PREFIX + lectureId + "/qa",
                    QaStreamMessage.chunk(lectureId, "*Found " + chunks.size() + " relevant sections. Generating answer...*\n\n"));

            // 2. Build Prompt
            String contextText = String.join("\n\n---\n\n", chunks);
            String prompt = String.format("""
                You are an expert teaching assistant helping a student understand their lecture material.
                
                Use ONLY the context below to answer the question. Be specific and educational.
                If the answer is not in the context, say "This topic isn't covered in the provided lecture material."
                Do not make up facts. Do not use outside knowledge.
                
                LECTURE CONTEXT:
                %s
                
                STUDENT QUESTION:
                %s
                
                ANSWER (be clear, specific, and explain concepts thoroughly):""", contextText, question);

            // 3. Stream from Ollama
            StringBuilder fullAnswer = new StringBuilder();
            AtomicReference<Throwable> streamError = new AtomicReference<>();

            Map<String, Object> options = new java.util.HashMap<>();
            options.put("num_ctx", 4096);
            options.put("temperature", 0.3);

            Map<String, Object> requestBody = new java.util.HashMap<>();
            requestBody.put("model", ollamaModel);
            requestBody.put("prompt", prompt);
            requestBody.put("stream", true);
            requestBody.put("options", options);

            Flux<String> chunkFlux = ollamaWebClient.post()
                    .uri("/api/generate")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToFlux(String.class);

            chunkFlux
                    .takeWhile(line -> !cancellationService.isCancelled(lectureId))
                    .doOnNext(line -> {
                        try {
                            JsonNode node = objectMapper.readTree(line);
                            String token = node.path("response").asText("");
                            if (!token.isEmpty()) {
                                fullAnswer.append(token);
                                messagingTemplate.convertAndSend(
                                        TOPIC_PREFIX + lectureId + "/qa",
                                        QaStreamMessage.chunk(lectureId, token));
                            }
                        } catch (Exception e) {
                            log.warn("Failed to parse Ollama QA chunk for lectureId={}: {}", lectureId, e.getMessage());
                        }
                    })
                    .doOnError(error -> {
                        log.error("QA stream error for lectureId={}: {}", lectureId, error.getMessage(), error);
                        streamError.set(error);
                    })
                    .blockLast();

            if (cancellationService.isCancelled(lectureId)) {
                messagingTemplate.convertAndSend(
                        TOPIC_PREFIX + lectureId + "/qa",
                        QaStreamMessage.chunk(lectureId, "\n\n*[Generation stopped by user]*"));
            }

            if (streamError.get() != null) {
                sendError(lectureId, "Streaming answer failed: " + streamError.get().getMessage());
                return CompletableFuture.completedFuture(null);
            }

            String answerText = fullAnswer.toString();
            messagingTemplate.convertAndSend(
                    TOPIC_PREFIX + lectureId + "/qa",
                    QaStreamMessage.completed(lectureId, question, answerText, chunks, chunks.size()));

        } catch (Exception e) {
            log.error("Streaming Q&A FAILED for lectureId={}: {}", lectureId, e.getMessage(), e);
            sendError(lectureId, "Q&A failed: " + e.getMessage());
        }

        return CompletableFuture.completedFuture(null);
    }

    private void sendError(String lectureId, String errorMessage) {
        messagingTemplate.convertAndSend(
                TOPIC_PREFIX + lectureId + "/qa",
                QaStreamMessage.error(lectureId, errorMessage));
    }
}
