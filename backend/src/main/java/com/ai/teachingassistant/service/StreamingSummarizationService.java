package com.ai.teachingassistant.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.ai.teachingassistant.dto.SummaryStreamMessage;
import com.ai.teachingassistant.model.Lecture;
import com.ai.teachingassistant.repository.LectureRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * StreamingSummarizationService — streams Ollama LLM output chunk-by-chunk
 * over WebSocket (STOMP) so the React frontend can display a live typing
 * effect.
 *
 * <p>
 * For long documents, uses a <b>Map-Reduce</b> strategy:
 * </p>
 * <ol>
 * <li><b>Split</b> — Divide the full lecture text into overlapping chunks.</li>
 * <li><b>Map</b> — Summarize each chunk individually (non-streaming,
 * sequential).</li>
 * <li><b>Reduce</b> — Combine all chunk summaries into one final structured
 * summary, streamed live to the WebSocket.</li>
 * </ol>
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
 *        │  SUMMARY_PROGRESS ◄────────────────────  │  │ StreamingSummarizationSvc  │
 *        │  SUMMARY_CHUNK  ◄────────────────────────┤  │                            │
 *        │  SUMMARY_CHUNK  ◄────────────────────────┤  │  WebClient → Ollama        │
 *        │  SUMMARY_COMPLETED ◄─────────────────────┤  │  stream=true (Flux)        │
 *        │                                          │  │  SimpMessagingTemplate     │
 *        │                                          │  │  → /topic/lectures/{id}    │
 *        │                                          └──│                            │
 *        │                                             │  On complete: save to DB   │
 *        │                                             └────────────────────────────┘
 * </pre>
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

    /** Max characters per chunk for the MAP phase. */
    private static final int CHUNK_SIZE = 8000;

    /** Overlap between adjacent chunks (chars). */
    private static final int CHUNK_OVERLAP = 500;

    /** Below this threshold, use single-pass (no Map-Reduce overhead). */
    private static final int SINGLE_PASS_THRESHOLD = 10000;

    /**
     * Kicks off streaming summarization on a background thread.
     *
     * <p>
     * For short documents: streams directly to WebSocket (same as before).
     * For long documents: runs Map-Reduce, then streams the final Reduce phase.
     * </p>
     *
     * @param lectureId the UUID of the lecture to summarize
     */
    @Async("summarizationExecutor")
    public CompletableFuture<Void> streamSummarization(String lectureId) {
        log.info("Streaming summarization starting for lectureId={}", lectureId);
        long startTime = System.currentTimeMillis();

        try {
            // ── 1. Load lecture from DB ──────────────────────────────────────
            Lecture lecture = lectureRepository.findById(lectureId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Lecture not found: " + lectureId));

            // ── 2. Check for cached summary ─────────────────────────────────
            String existingSummary = lecture.getSummary();
            if (existingSummary != null && !existingSummary.isBlank()) {
                log.info("Cache HIT for lectureId={} — streaming cached summary " +
                        "({} chars) word-by-word.", lectureId, existingSummary.length());
                streamCachedSummary(lectureId, existingSummary);
                return CompletableFuture.completedFuture(null);
            }

            // ── 3. No cached summary — generate from scratch ─────────────────
            String extractedText = lecture.getOriginalText();
            if (extractedText == null || extractedText.isBlank()) {
                sendError(lectureId, "No extracted text available for this lecture.");
                return CompletableFuture.completedFuture(null);
            }

            String completeSummary;

            if (extractedText.length() <= SINGLE_PASS_THRESHOLD) {
                // ── Short document: stream directly ──────────────────────────
                log.info("Short document ({} chars) — single-pass streaming.",
                        extractedText.length());
                completeSummary = streamSinglePass(lectureId, extractedText);
            } else {
                // ── Long document: Map-Reduce then stream ────────────────────
                log.info("Long document ({} chars) — Map-Reduce streaming.",
                        extractedText.length());
                completeSummary = mapReduceStream(lectureId, extractedText);
            }

            // ── Save full summary to DB ──────────────────────────────────────
            if (completeSummary == null || completeSummary.isBlank()) {
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

            // ── Send completion event ────────────────────────────────────────
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

    // ═════════════════════════════════════════════════════════════════════════
    // SINGLE-PASS STREAMING (short documents)
    // ═════════════════════════════════════════════════════════════════════════
    // CACHED SUMMARY STREAMING
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Streams a previously-cached summary word-by-word over WebSocket,
     * simulating the live typing effect. This avoids redundant LLM calls
     * while keeping the UX consistent.
     *
     * <p>
     * Handles two storage formats:
     * </p>
     * <ul>
     * <li><b>JSON</b> — saved by {@code LectureService.processLecture()}
     * (synchronous path). We extract the {@code markdownSummary} field.</li>
     * <li><b>Raw text</b> — saved by the streaming path. Streamed as-is.</li>
     * </ul>
     */
    private void streamCachedSummary(String lectureId, String cachedSummary) {
        try {
            // ── Resolve the human-readable summary text ──────────────────
            String textToStream = extractReadableSummary(cachedSummary);

            if (textToStream == null || textToStream.isBlank()) {
                log.warn("Cached summary resolved to empty for lectureId={}", lectureId);
                sendError(lectureId, "Cached summary is empty. Please re-upload.");
                return;
            }

            log.info("Streaming cached summary for lectureId={}: {} chars",
                    lectureId, textToStream.length());

            // ── Stream in fixed-size character chunks ─────────────────────
            // Sending ~50 chars per batch with a short delay gives a smooth
            // typing effect that works reliably (unlike regex word splitting).
            int chunkSize = 50;
            int delayMs = 20;

            for (int i = 0; i < textToStream.length(); i += chunkSize) {
                int end = Math.min(i + chunkSize, textToStream.length());
                String chunk = textToStream.substring(i, end);

                messagingTemplate.convertAndSend(
                        TOPIC_PREFIX + lectureId,
                        SummaryStreamMessage.chunk(lectureId, chunk));

                Thread.sleep(delayMs);
            }

            // ── Send completion ──────────────────────────────────────────
            messagingTemplate.convertAndSend(
                    TOPIC_PREFIX + lectureId,
                    SummaryStreamMessage.completed(lectureId, textToStream));

            log.info("Cached summary streamed successfully for lectureId={}", lectureId);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Cached summary streaming interrupted for lectureId={}", lectureId);
            sendError(lectureId, "Streaming was interrupted.");
        }
    }

    /**
     * Extracts human-readable summary text from whatever is stored in the
     * {@code summary} column.
     *
     * <ul>
     * <li>If it starts with "{" → it's JSON from the synchronous pipeline.
     * Extract {@code markdownSummary} (preferred) or fall back to
     * {@code detailedExplanation}.</li>
     * <li>Otherwise → it's raw LLM text from the streaming pipeline.
     * Return as-is.</li>
     * </ul>
     */
    private String extractReadableSummary(String storedSummary) {
        if (storedSummary == null || storedSummary.isBlank())
            return null;

        String trimmed = storedSummary.trim();

        // Not JSON — return raw text directly
        if (!trimmed.startsWith("{")) {
            return trimmed;
        }

        // It's JSON — parse and extract the markdown summary
        try {
            JsonNode root = objectMapper.readTree(trimmed);

            // Prefer markdownSummary (contains the full formatted summary)
            String markdown = root.path("markdownSummary").asText("");
            if (!markdown.isBlank()) {
                return markdown;
            }

            // Fallback: build readable text from individual fields
            StringBuilder sb = new StringBuilder();
            String title = root.path("title").asText("");
            String overview = root.path("overview").asText("");
            String detailed = root.path("detailedExplanation").asText("");

            if (!title.isBlank())
                sb.append("# ").append(title).append("\n\n");
            if (!overview.isBlank())
                sb.append("## Overview\n").append(overview).append("\n\n");

            JsonNode keyPoints = root.path("keyPoints");
            if (keyPoints.isArray() && keyPoints.size() > 0) {
                sb.append("## Key Concepts\n");
                for (JsonNode kp : keyPoints)
                    sb.append("- ").append(kp.asText()).append("\n");
                sb.append("\n");
            }

            JsonNode definitions = root.path("definitions");
            if (definitions.isArray() && definitions.size() > 0) {
                sb.append("## Definitions\n");
                for (JsonNode d : definitions)
                    sb.append("- ").append(d.asText()).append("\n");
                sb.append("\n");
            }

            if (!detailed.isBlank())
                sb.append("## Detailed Explanation\n").append(detailed).append("\n\n");

            JsonNode examPoints = root.path("examPoints");
            if (examPoints.isArray() && examPoints.size() > 0) {
                sb.append("## Exam-Focused Takeaways\n");
                for (JsonNode ep : examPoints)
                    sb.append("- ").append(ep.asText()).append("\n");
                sb.append("\n");
            }

            JsonNode furtherReading = root.path("furtherReading");
            if (furtherReading.isArray() && furtherReading.size() > 0) {
                sb.append("## Further Reading\n");
                for (JsonNode fr : furtherReading)
                    sb.append("- ").append(fr.asText()).append("\n");
                sb.append("\n");
            }

            String result = sb.toString().trim();
            return result.isEmpty() ? trimmed : result;

        } catch (Exception e) {
            log.warn("Failed to parse cached summary as JSON for streaming: {}", e.getMessage());
            // If JSON parsing fails, just stream whatever we have
            return trimmed;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Streams the summarization directly for short documents (original flow).
     */
    private String streamSinglePass(String lectureId, String extractedText) {
        String prompt = buildFinalPrompt(extractedText);
        return streamFromOllama(lectureId, prompt, 3000);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // MAP-REDUCE STREAMING (long documents)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * For long documents:
     * 1. Split text into chunks
     * 2. MAP: summarize each chunk (non-streaming, to keep it fast)
     * 3. Send progress updates to WebSocket so the user sees activity
     * 4. REDUCE: stream the final combined summary to WebSocket
     */
    private String mapReduceStream(String lectureId, String extractedText) {
        List<String> chunks = splitIntoChunks(extractedText, CHUNK_SIZE, CHUNK_OVERLAP);
        log.info("Split document into {} chunks for Map-Reduce.", chunks.size());

        // Notify the user that chunked processing has started
        messagingTemplate.convertAndSend(
                TOPIC_PREFIX + lectureId,
                SummaryStreamMessage.chunk(lectureId,
                        "📖 *Processing long document (" + chunks.size()
                                + " sections)...*\n\n"));

        // ── MAP phase: summarize each chunk (non-streaming) ──────────────
        List<String> chunkSummaries = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            log.info("MAP phase: summarizing chunk {}/{} ({} chars)",
                    i + 1, chunks.size(), chunks.get(i).length());

            // Send progress update to WebSocket
            messagingTemplate.convertAndSend(
                    TOPIC_PREFIX + lectureId,
                    SummaryStreamMessage.chunk(lectureId,
                            "📝 *Analyzing section " + (i + 1) + "/" + chunks.size() + "...*\n"));

            String chunkPrompt = buildChunkPrompt(chunks.get(i), i + 1, chunks.size());
            String chunkSummary = callOllamaNonStreaming(chunkPrompt);

            if (chunkSummary != null && !chunkSummary.isBlank()) {
                chunkSummaries.add(chunkSummary.trim());
            } else {
                log.warn("Chunk {}/{} returned empty — skipping.", i + 1, chunks.size());
            }
        }

        if (chunkSummaries.isEmpty()) {
            sendError(lectureId, "All chunk summaries were empty. The AI model may be unavailable.");
            return null;
        }

        // ── REDUCE phase: stream the final combined summary ──────────────
        log.info("REDUCE phase: combining {} chunk summaries into final summary.",
                chunkSummaries.size());

        // Clear progress messages and begin the real summary stream
        messagingTemplate.convertAndSend(
                TOPIC_PREFIX + lectureId,
                SummaryStreamMessage.chunk(lectureId,
                        "\n✅ *All sections analyzed. Generating final summary...*\n\n"));

        String combinedSummaries = String.join("\n\n---\n\n", chunkSummaries);
        String reducePrompt = buildReducePrompt(combinedSummaries);

        // Stream the reduce phase — this is what the user sees as the "typing" effect
        return streamFromOllama(lectureId, reducePrompt, 4000);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // OLLAMA COMMUNICATION
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Streams a prompt to Ollama and pushes each token to the WebSocket topic.
     * Returns the full accumulated response.
     */
    private String streamFromOllama(String lectureId, String prompt, int maxTokens) {
        StringBuilder fullSummary = new StringBuilder();
        AtomicReference<Throwable> streamError = new AtomicReference<>();

        Map<String, Object> requestBody = Map.of(
                "model", ollamaModel,
                "prompt", prompt,
                "stream", true,
                "num_predict", maxTokens);

        Flux<String> chunkFlux = ollamaWebClient.post()
                .bodyValue(requestBody)
                .retrieve()
                .bodyToFlux(String.class);

        chunkFlux
                .doOnNext(line -> {
                    try {
                        JsonNode node = objectMapper.readTree(line);
                        String token = node.path("response").asText("");

                        if (!token.isEmpty()) {
                            fullSummary.append(token);

                            messagingTemplate.convertAndSend(
                                    TOPIC_PREFIX + lectureId,
                                    SummaryStreamMessage.chunk(lectureId, token));
                        }

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

        if (streamError.get() != null) {
            sendError(lectureId, "Streaming failed: " + streamError.get().getMessage());
            return null;
        }

        return fullSummary.toString();
    }

    /**
     * Calls Ollama synchronously (non-streaming) for the MAP phase.
     * Each chunk summary is collected before moving to the next chunk.
     */
    private String callOllamaNonStreaming(String prompt) {
        try {
            Map<String, Object> requestBody = Map.of(
                    "model", ollamaModel,
                    "prompt", prompt,
                    "stream", false,
                    "num_predict", 1500); // Chunk summaries should be concise

            String responseBody = ollamaWebClient.post()
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(); // safe — on @Async thread

            if (responseBody == null)
                return null;

            JsonNode root = objectMapper.readTree(responseBody);
            return root.path("response").asText("");
        } catch (Exception e) {
            log.error("Non-streaming Ollama call failed: {}", e.getMessage(), e);
            return null;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // TEXT SPLITTING
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Splits text into overlapping chunks, trying to break at paragraph or
     * sentence boundaries.
     */
    private List<String> splitIntoChunks(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        int start = 0;

        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());

            // Try to find a natural break point near the end
            if (end < text.length()) {
                int naturalBreak = findNaturalBreak(text, start + (chunkSize / 2), end);
                if (naturalBreak > start) {
                    end = naturalBreak;
                }
            }

            chunks.add(text.substring(start, end).trim());

            start = end - overlap;
            if (start <= 0 && end >= text.length())
                break;
            if (start >= text.length())
                break;
        }

        return chunks;
    }

    /**
     * Finds the best natural break point (paragraph > sentence > newline).
     */
    private int findNaturalBreak(String text, int earliest, int end) {
        int idx = text.lastIndexOf("\n\n", end);
        if (idx >= earliest)
            return idx + 2;

        idx = text.lastIndexOf(". ", end);
        if (idx >= earliest)
            return idx + 2;

        idx = text.lastIndexOf("\n", end);
        if (idx >= earliest)
            return idx + 1;

        return end;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PROMPTS
    // ═════════════════════════════════════════════════════════════════════════

    /** MAP phase prompt: summarize a single chunk. */
    private String buildChunkPrompt(String chunkText, int chunkNumber, int totalChunks) {
        return """
                You are an expert university-level teaching assistant.
                You are reading PART %d of %d of a lecture document.

                Summarize this section thoroughly. Include:
                - All key concepts and ideas mentioned
                - Important definitions and terminology
                - Any examples or case studies
                - Exam-worthy points

                Write in clear, full sentences. Be comprehensive — do not skip important details.
                Keep your summary between 300-600 words.

                --- LECTURE SECTION %d/%d ---
                %s
                --- END SECTION ---
                """
                .formatted(chunkNumber, totalChunks, chunkNumber, totalChunks, chunkText);
    }

    /** REDUCE phase prompt: combine all chunk summaries into final structure. */
    private String buildReducePrompt(String combinedSummaries) {
        return """
                You are an expert university-level teaching assistant.
                Below are summaries of different sections of a lecture document.
                Your job is to combine them into ONE comprehensive, well-structured summary.

                Eliminate redundancy. Merge overlapping points. Ensure nothing important is lost.

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

                --- SECTION SUMMARIES ---
                %s
                --- END ---
                """
                .formatted(combinedSummaries);
    }

    /** Single-pass prompt for short documents. */
    private String buildFinalPrompt(String lectureText) {
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
                .formatted(lectureText);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    /** Sends an error message over the WebSocket topic. */
    private void sendError(String lectureId, String errorMessage) {
        messagingTemplate.convertAndSend(
                TOPIC_PREFIX + lectureId,
                SummaryStreamMessage.error(lectureId, errorMessage));
    }
}
