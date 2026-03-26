package com.ai.teachingassistant.service;

import com.ai.teachingassistant.dto.AskQuestionResponse;
import com.ai.teachingassistant.dto.QaStreamMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * RagService implements Retrieval-Augmented Generation (RAG) for lecture Q&A.
 *
 * <h2>How it works</h2>
 * <ol>
 * <li><b>Index</b> — When a PDF is uploaded, {@link #indexLecture} splits the
 * extracted text into overlapping chunks, embeds each chunk using OpenAI's
 * embedding model, and stores the vectors in pgvector alongside the lectureId
 * as metadata.</li>
 *
 * <li><b>Ask</b> — When the user asks a question, {@link #askQuestion} embeds
 * the question, finds the top-K most similar chunks from pgvector (scoped to
 * the specific lecture), then passes those chunks + the question to an LLM to
 * produce a precise, grounded answer.</li>
 * </ol>
 *
 * <p>
 * Because only the relevant chunks are sent to the LLM (not the whole PDF),
 * this is fast, cheap, and works on arbitrarily long documents.
 * <p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

        private final VectorStore vectorStore;
        private final ChatClient chatClient;
        private final WebClient ollamaWebClient;
        private final SimpMessagingTemplate messagingTemplate;
        private final ObjectMapper objectMapper;
        private final StreamCancellationService cancellationService;

        @Value("${ollama.model:llama3.2}")
        private String ollamaModel;

        /** STOMP destination prefix for streaming Q&A messages. */
        private static final String QA_TOPIC_PREFIX = "/topic/lectures/";

        // ── Tuning constants ────────────────────────────────────────────────────

        /** Maximum characters per chunk sent to the embedding model. */
        private static final int CHUNK_SIZE = 800;

        /**
         * Overlap between consecutive chunks in characters.
         * Prevents important context that spans a chunk boundary from being lost.
         */
        private static final int CHUNK_OVERLAP = 150;

        /** Number of similar chunks to retrieve per question. */
        private static final int TOP_K = 5;

        // ── Public API ──────────────────────────────────────────────────────────

        /**
         * Chunks the extracted PDF text, embeds each chunk, and stores them in
         * the pgvector {@link VectorStore} tagged with the given {@code lectureId}.
         *
         * <p>
         * This is called automatically by {@link LectureService} right after a new
         * lecture is saved to the database. Cache hits skip indexing because the
         * vectors are already stored from the first upload.
         * </p>
         *
         * @param lectureId     the UUID of the saved
         *                      {@link com.ai.teachingassistant.model.Lecture}
         * @param extractedText the full text extracted from the PDF
         */
        public void indexLecture(String lectureId, String extractedText) {
                indexLectureAndCount(lectureId, extractedText);
        }

        /**
         * Same as {@link #indexLecture} but also returns the number of chunks stored —
         * used by the quick-index flow to include the count in the API response.
         */
        public int indexLectureAndCount(String lectureId, String extractedText) {
                List<String> chunks = chunkText(extractedText);
                log.info("Indexing {} chunks for lectureId={}", chunks.size(), lectureId);

                List<Document> documents = chunks.stream()
                                .map(chunk -> new Document(chunk, Map.of("lectureId", lectureId)))
                                .collect(Collectors.toList());

                vectorStore.add(documents);
                log.info("Successfully indexed {} chunks for lectureId={}", documents.size(), lectureId);
                return documents.size();
        }

        /**
         * Answers a student question using only the content of a specific lecture.
         *
         * <ol>
         * <li>Embeds the question via OpenAI.</li>
         * <li>Searches pgvector for the top-{@value #TOP_K} most similar chunks
         * scoped to the given {@code lectureId}.</li>
         * <li>Constructs a prompt that includes those chunks as context.</li>
         * <li>Calls the LLM and returns a structured {@link AskQuestionResponse}.</li>
         * </ol>
         *
         * @param lectureId the UUID of the lecture to query against
         * @param question  the student's natural-language question
         * @return a structured response containing the answer and the source chunks
         *         used
         */
        public AskQuestionResponse askQuestion(String lectureId, String question) {
                log.info("RAG Q&A: lectureId={}, question='{}'", lectureId, question);

                // ── Step 1: retrieve the most relevant chunks ────────────────────
                var b = new FilterExpressionBuilder();
                List<Document> relevantDocs = vectorStore.similaritySearch(
                                SearchRequest.builder()
                                                .query(question)
                                                .topK(TOP_K)
                                                .filterExpression(b.eq("lectureId", lectureId).build())
                                                .build());

                // Guard: some VectorStore implementations may return null on no results
                if (relevantDocs == null || relevantDocs.isEmpty()) {
                        log.warn("No relevant chunks found in vector store for lectureId={}", lectureId);
                        return AskQuestionResponse.builder()
                                        .lectureId(lectureId)
                                        .question(question)
                                        .answer("I couldn't find relevant content in this lecture to answer your question. "
                                                        + "Try rephrasing or ask about a different topic covered in the lecture.")
                                        .sourceChunks(List.of())
                                        .chunksUsed(0)
                                        .build();
                }

                List<String> sourceChunks = (relevantDocs == null ? List.<Document>of() : relevantDocs)
                                .stream()
                                .map(Document::getText)
                                .collect(Collectors.toList());

                log.debug("Retrieved {} relevant chunks for Q&A", sourceChunks.size());

                // ── Step 2: build a context-grounded prompt ──────────────────────
                String context = String.join("\n\n---\n\n", sourceChunks);

                String prompt = """
                                You are a helpful teaching assistant. A student is asking a question about
                                their lecture. Answer using ONLY the lecture content provided below.

                                Rules:
                                - Be clear and concise.
                                - If the answer is not in the provided content, say exactly:
                                  "I couldn't find that in this lecture. Try asking about something else covered here."
                                - Do NOT make up information beyond what is in the content.
                                - Use bullet points or numbered lists when the answer has multiple parts.

                                --- LECTURE CONTENT ---
                                %s
                                --- END OF LECTURE CONTENT ---

                                STUDENT QUESTION: %s

                                ANSWER:
                                """.formatted(context, question);

                // ── Step 3: call the LLM with the focused context ────────────────
                String answer = chatClient.prompt(prompt).call().content();
                log.info("RAG answer generated for lectureId={}, chunksUsed={}", lectureId, sourceChunks.size());

                return AskQuestionResponse.builder()
                                .lectureId(lectureId)
                                .question(question)
                                .answer(answer)
                                .sourceChunks(sourceChunks)
                                .chunksUsed(sourceChunks.size())
                                .build();
        }

        // ── Streaming Q&A ───────────────────────────────────────────────────────

        /**
         * Streams an answer to a student question over WebSocket.
         *
         * <ol>
         * <li>Retrieves the top-K most relevant chunks from pgvector (fast).</li>
         * <li>Builds a context-grounded prompt.</li>
         * <li>Calls Ollama with {@code stream: true} via WebClient.</li>
         * <li>Pushes each answer token as an {@code ANSWER_CHUNK} to
         * {@code /topic/lectures/{lectureId}/qa}.</li>
         * <li>On completion, sends {@code ANSWER_COMPLETED} with the full answer
         * and source chunks.</li>
         * </ol>
         *
         * @param lectureId the UUID of the lecture to query
         * @param question  the student's question
         */
        @Async("summarizationExecutor")
        public CompletableFuture<Void> streamAnswer(String lectureId, String question) {
                log.info("Streaming RAG Q&A: lectureId={}, question='{}'", lectureId, question);
                String destination = QA_TOPIC_PREFIX + lectureId + "/qa";

                // Clear any previous cancellation
                cancellationService.clearCancellation(lectureId);

                try {
                        // ── Step 1: retrieve the most relevant chunks ────────────────
                        var b = new FilterExpressionBuilder();
                        List<Document> relevantDocs = vectorStore.similaritySearch(
                                        SearchRequest.builder()
                                                        .query(question)
                                                        .topK(TOP_K)
                                                        .filterExpression(b.eq("lectureId", lectureId).build())
                                                        .build());

                        if (relevantDocs == null || relevantDocs.isEmpty()) {
                                log.warn("No relevant chunks found for streaming Q&A, lectureId={}", lectureId);
                                messagingTemplate.convertAndSend(destination,
                                                QaStreamMessage.completed(lectureId, question,
                                                                "I couldn't find relevant content in this lecture to answer your question. "
                                                                                + "Try rephrasing or ask about a different topic covered in the lecture.",
                                                                List.of(), 0));
                                return CompletableFuture.completedFuture(null);
                        }

                        List<String> sourceChunks = relevantDocs.stream()
                                        .map(Document::getText)
                                        .collect(Collectors.toList());

                        log.debug("Retrieved {} relevant chunks for streaming Q&A", sourceChunks.size());

                        // ── Step 2: build the context-grounded prompt ─────────────────
                        String context = String.join("\n\n---\n\n", sourceChunks);
                        String prompt = """
                                        You are a helpful teaching assistant. A student is asking a question about
                                        their lecture. Answer using ONLY the lecture content provided below.

                                        Rules:
                                        - Be clear and concise.
                                        - If the answer is not in the provided content, say exactly:
                                          "I couldn't find that in this lecture. Try asking about something else covered here."
                                        - Do NOT make up information beyond what is in the content.
                                        - Use bullet points or numbered lists when the answer has multiple parts.

                                        --- LECTURE CONTENT ---
                                        %s
                                        --- END OF LECTURE CONTENT ---

                                        STUDENT QUESTION: %s

                                        ANSWER:
                                        """
                                        .formatted(context, question);

                        // ── Step 3: stream from Ollama via WebClient ──────────────────
                        StringBuilder fullAnswer = new StringBuilder();
                        AtomicReference<Throwable> streamError = new AtomicReference<>();

                        Map<String, Object> requestBody = Map.of(
                                        "model", ollamaModel,
                                        "prompt", prompt,
                                        "stream", true,
                                        "num_predict", 2000);

                        Flux<String> chunkFlux = ollamaWebClient.post()
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
                                                                messagingTemplate.convertAndSend(destination,
                                                                                QaStreamMessage.chunk(lectureId,
                                                                                                token));
                                                        }
                                                } catch (Exception e) {
                                                        log.warn("Failed to parse Ollama Q&A chunk for lectureId={}: {}",
                                                                        lectureId, e.getMessage());
                                                }
                                        })
                                        .doOnError(error -> {
                                                log.error("Stream error during Q&A for lectureId={}: {}",
                                                                lectureId, error.getMessage(), error);
                                                streamError.set(error);
                                        })
                                        .blockLast(); // safe — running on dedicated @Async thread

                        // If cancelled, append a stop notice
                        if (cancellationService.isCancelled(lectureId)) {
                                log.info("Q&A streaming CANCELLED by user for lectureId={}", lectureId);
                                messagingTemplate.convertAndSend(destination,
                                                QaStreamMessage.chunk(lectureId,
                                                                "\n\n*[Generation stopped by user]*"));
                        }

                        // ── Step 4: handle errors / send completion ───────────────────
                        if (streamError.get() != null) {
                                messagingTemplate.convertAndSend(destination,
                                                QaStreamMessage.error(lectureId,
                                                                "Streaming failed: " + streamError.get().getMessage()));
                                return CompletableFuture.completedFuture(null);
                        }

                        String answer = fullAnswer.toString();
                        if (answer.isBlank()) {
                                messagingTemplate.convertAndSend(destination,
                                                QaStreamMessage.error(lectureId, "Ollama returned an empty response."));
                                return CompletableFuture.completedFuture(null);
                        }

                        log.info("Streaming Q&A complete: lectureId={}, answerLen={}, chunksUsed={}",
                                        lectureId, answer.length(), sourceChunks.size());

                        messagingTemplate.convertAndSend(destination,
                                        QaStreamMessage.completed(lectureId, question,
                                                        answer, sourceChunks, sourceChunks.size()));

                } catch (Exception e) {
                        log.error("Streaming Q&A FAILED for lectureId={}: {}",
                                        lectureId, e.getMessage(), e);
                        messagingTemplate.convertAndSend(destination,
                                        QaStreamMessage.error(lectureId, "Q&A failed: " + e.getMessage()));
                }

                return CompletableFuture.completedFuture(null);
        }

        // ── Private helpers ─────────────────────────────────────────────────────

        /**
         * Splits a large text into overlapping fixed-size character chunks.
         *
         * <p>
         * Using overlap ({@value #CHUNK_OVERLAP} chars) ensures that sentences
         * or ideas that straddle a chunk boundary are fully represented in at
         * least one chunk.
         * </p>
         */
        private List<String> chunkText(String text) {
                List<String> chunks = new ArrayList<>();
                int step = CHUNK_SIZE - CHUNK_OVERLAP;
                int i = 0;

                while (i < text.length()) {
                        int end = Math.min(i + CHUNK_SIZE, text.length());
                        String chunk = text.substring(i, end).trim();
                        if (!chunk.isEmpty()) {
                                chunks.add(chunk);
                        }
                        i += step;
                }

                log.debug("Chunked text ({} chars) into {} chunks (size={}, overlap={})",
                                text.length(), chunks.size(), CHUNK_SIZE, CHUNK_OVERLAP);
                return chunks;
        }
}
