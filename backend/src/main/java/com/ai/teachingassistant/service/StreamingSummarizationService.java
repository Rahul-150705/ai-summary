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
    private final StreamCancellationService cancellationService;

    @Value("${ollama.model:llama3.2}")
    private String ollamaModel;

    /** STOMP destination prefix for streaming summary messages. */
    private static final String TOPIC_PREFIX = "/topic/lectures/";

    /** Max characters per chunk for the MAP phase (~1500 tokens). */
    private static final int CHUNK_SIZE = 6000;

    /** Overlap between adjacent chunks (chars). */
    private static final int CHUNK_OVERLAP = 200;

    /** Below this threshold, use single-pass (no Map-Reduce overhead). */
    private static final int SINGLE_PASS_THRESHOLD = 8000;

    // ── CPU Tuning for Intel i5-1240P (12 cores / 16 logical threads, 16GB RAM) ──
    // Leave 2 logical threads free for OS + JVM GC to avoid starvation.
    private static final int NUM_THREADS = 14;
    // num_ctx for MAP chunks: phi3 chunk prompts are short, keep small for fast prefill.
    private static final int CTX_CHUNK   = 1024;
    // num_ctx for REDUCE phase: holds ALL chunk summaries combined.
    private static final int CTX_REDUCE  = 8192;
    // num_batch: tokens processed per forward pass. Higher = better CPU throughput.
    private static final int NUM_BATCH   = 512;
    // keep_alive=-1 → model stays loaded in RAM forever (no cold-start between chunks).
    private static final String KEEP_ALIVE = "-1";

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

        // Clear any previous cancellation for this lecture
        cancellationService.clearCancellation(lectureId);

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

            // ── 3b. Send document info to the user ───────────────────────────
            int estimatedWords = extractedText.length() / 5; // rough chars-to-words
            int estimatedChunks = Math.max(1,
                    (int) Math.ceil((double) extractedText.length() / (CHUNK_SIZE - CHUNK_OVERLAP)));
            String docInfo = String.format(
                    "📄 *Document: %d pages, ~%,d words. Processing in %s...*\n\n",
                    lecture.getPageCount(), estimatedWords,
                    extractedText.length() <= SINGLE_PASS_THRESHOLD
                            ? "single pass" : estimatedChunks + " sections");
            messagingTemplate.convertAndSend(
                    TOPIC_PREFIX + lectureId,
                    SummaryStreamMessage.chunk(lectureId, docInfo));

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

            log.info("Streaming cached summary word-by-word for lectureId={}: {} chars",
                    lectureId, textToStream.length());

            // ── Stream word-by-word ──────────────────────────────────────
            // Split on whitespace boundaries while keeping the delimiter
            // attached to the preceding word (e.g. "Hello " , "world\n").
            // This mirrors how Ollama sends tokens one at a time.
            String[] words = textToStream.split("(?<=\\s)");
            StringBuilder partialSummary = new StringBuilder();

            for (String word : words) {
                if (word.isEmpty()) continue;

                // ── Check if the user requested a stop ───────────────────
                if (cancellationService.isCancelled(lectureId)) {
                    log.info("Cached summary streaming CANCELLED by user for lectureId={}", lectureId);
                    messagingTemplate.convertAndSend(
                            TOPIC_PREFIX + lectureId,
                            SummaryStreamMessage.chunk(lectureId, "\n\n*[Generation stopped by user]*"));
                    break;
                }

                partialSummary.append(word);
                messagingTemplate.convertAndSend(
                        TOPIC_PREFIX + lectureId,
                        SummaryStreamMessage.chunk(lectureId, word));

                // 30ms per word ≈ ~33 words/sec — feels like fast AI typing
                Thread.sleep(30);
            }

            // ── Send completion (with whatever was streamed) ─────────────
            messagingTemplate.convertAndSend(
                    TOPIC_PREFIX + lectureId,
                    SummaryStreamMessage.completed(lectureId, partialSummary.toString()));

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

            // Fallback: build readable text from individual JSON fields
            StringBuilder sb = new StringBuilder();
            String title = root.path("title").asText("");
            String mainTopic = root.path("mainTopic").asText("");
            String structureOverview = root.path("structureOverview").asText("");

            if (!title.isBlank())
                sb.append("# ").append(title).append("\n\n");
            if (!mainTopic.isBlank())
                sb.append("## Main Topic & Purpose\n").append(mainTopic).append("\n\n");

            JsonNode keyPoints = root.path("keyPoints");
            if (keyPoints.isArray() && keyPoints.size() > 0) {
                sb.append("## Key Points & Arguments\n");
                for (JsonNode kp : keyPoints)
                    sb.append("- ").append(kp.asText()).append("\n");
                sb.append("\n");
            }

            JsonNode importantDetails = root.path("importantDetails");
            if (importantDetails.isArray() && importantDetails.size() > 0) {
                sb.append("## Important Details\n");
                for (JsonNode d : importantDetails)
                    sb.append("- ").append(d.asText()).append("\n");
                sb.append("\n");
            }

            if (!structureOverview.isBlank())
                sb.append("## Structure Overview\n").append(structureOverview).append("\n\n");

            JsonNode conclusions = root.path("conclusions");
            if (conclusions.isArray() && conclusions.size() > 0) {
                sb.append("## Conclusions & Recommendations\n");
                for (JsonNode c : conclusions)
                    sb.append("- ").append(c.asText()).append("\n");
                sb.append("\n");
            }

            JsonNode notableQuotes = root.path("notableQuotes");
            if (notableQuotes.isArray() && notableQuotes.size() > 0) {
                sb.append("## Notable Quotes\n");
                for (JsonNode q : notableQuotes)
                    sb.append("- ").append(q.asText()).append("\n");
                sb.append("\n");
            }

            JsonNode additionalNotes = root.path("additionalNotes");
            if (additionalNotes.isArray() && additionalNotes.size() > 0) {
                sb.append("## Additional Notes\n");
                for (JsonNode n : additionalNotes)
                    sb.append("- ").append(n.asText()).append("\n");
                sb.append("\n");
            }

            String result = sb.toString().trim();
            return result.isEmpty() ? trimmed : result;

        } catch (Exception e) {
            log.warn("Failed to parse cached summary as JSON for streaming: {}", e.getMessage());
            return trimmed;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Streams the summarization directly for short documents (original flow).
     */
    private String streamSinglePass(String lectureId, String extractedText) {
        String prompt = buildFinalPrompt(extractedText);
        return streamFromOllama(lectureId, prompt, 2000);
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

        // ── MAP phase: stream each chunk summary to the user ─────────────
        List<String> chunkSummaries = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            // Check cancellation before each chunk
            if (cancellationService.isCancelled(lectureId)) {
                log.info("Map-Reduce CANCELLED during MAP phase for lectureId={}", lectureId);
                break;
            }

            log.info("MAP phase: streaming chunk {}/{} ({} chars)",
                    i + 1, chunks.size(), chunks.get(i).length());

            // Send section header to WebSocket
            messagingTemplate.convertAndSend(
                    TOPIC_PREFIX + lectureId,
                    SummaryStreamMessage.chunk(lectureId,
                            "\n📝 **Section " + (i + 1) + "/" + chunks.size() + ":**\n"));

            // Stream this chunk summary live to the user
            String chunkPrompt = buildChunkPrompt(chunks.get(i), i + 1, chunks.size());
            String chunkSummary = streamFromOllama(lectureId, chunkPrompt, 500);

            if (chunkSummary != null && !chunkSummary.isBlank()) {
                chunkSummaries.add(chunkSummary.trim());
            } else {
                log.warn("Chunk {}/{} returned empty — skipping.", i + 1, chunks.size());
            }

            // Add a visual separator between sections
            messagingTemplate.convertAndSend(
                    TOPIC_PREFIX + lectureId,
                    SummaryStreamMessage.chunk(lectureId, "\n\n---\n"));
        }

        if (chunkSummaries.isEmpty()) {
            sendError(lectureId, "All chunk summaries were empty. The AI model may be unavailable.");
            return null;
        }

        // ── REDUCE phase: stream the final combined summary ──────────────
        log.info("REDUCE phase: combining {} chunk summaries into final summary.",
                chunkSummaries.size());

        messagingTemplate.convertAndSend(
                TOPIC_PREFIX + lectureId,
                SummaryStreamMessage.chunk(lectureId,
                        "\n\n✅ **All sections analyzed. Generating final summary...**\n\n"));

        String combinedSummaries = String.join("\n\n---\n\n", chunkSummaries);

        // Safety cap: if chunk summaries are huge, truncate to avoid OOM
        // and Ollama context overflow. ~20k chars ≈ ~5k tokens.
        final int MAX_COMBINED_CHARS = 20_000;
        if (combinedSummaries.length() > MAX_COMBINED_CHARS) {
            log.warn("Combined summaries too long ({} chars) — truncating to {}.",
                    combinedSummaries.length(), MAX_COMBINED_CHARS);
            combinedSummaries = combinedSummaries.substring(0, MAX_COMBINED_CHARS)
                    + "\n\n[...truncated for length...]";
        }

        String reducePrompt = buildReducePrompt(combinedSummaries);

        // Stream the reduce phase with a larger context window (CTX_REDUCE=8192)
        // because the prompt contains ALL chunk summaries concatenated.
        return streamFromOllama(lectureId, reducePrompt, 2000, CTX_REDUCE);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // OLLAMA COMMUNICATION
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Streams a prompt to Ollama and pushes each token to the WebSocket topic.
     * Returns the full accumulated response.
     */
    private String streamFromOllama(String lectureId, String prompt, int maxTokens) {
        return streamFromOllama(lectureId, prompt, maxTokens, CTX_CHUNK);
    }

    private String streamFromOllama(String lectureId, String prompt, int maxTokens, int numCtx) {
        StringBuilder fullSummary = new StringBuilder();
        AtomicReference<Throwable> streamError = new AtomicReference<>();

        // ── CPU-max options for Intel i5-1240P ─────────────────────────────
        // num_thread  : use 14 of 16 logical threads; leave 2 for OS/JVM
        // num_ctx     : keep small for chunks (fast prefill), large for reduce
        // num_batch   : 512 tokens/pass → better CPU throughput vs default 128
        // mmap        : memory-map the model weights → stays in RAM between calls
        // low_vram    : prevent any iGPU offload attempt (would cause stalls)
        // repeat_penalty: reduces duplicate token loops (saves wasted tokens)
        Map<String, Object> options = new java.util.HashMap<>();
        options.put("num_thread",     NUM_THREADS);
        options.put("num_ctx",        numCtx);
        options.put("num_batch",      NUM_BATCH);
        options.put("mmap",           true);
        options.put("low_vram",       true);
        options.put("temperature",    0.3);
        options.put("repeat_penalty", 1.1);

        Map<String, Object> requestBody = new java.util.HashMap<>();
        requestBody.put("model",       ollamaModel);
        requestBody.put("prompt",      prompt);
        requestBody.put("stream",      true);
        requestBody.put("num_predict", maxTokens);
        // keep_alive=-1 → model stays loaded in RAM, no cold-start between chunks
        requestBody.put("keep_alive",  KEEP_ALIVE);
        requestBody.put("options",     options);

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

        // If cancelled, append a stop notice
        if (cancellationService.isCancelled(lectureId)) {
            log.info("Ollama streaming CANCELLED by user for lectureId={}", lectureId);
            messagingTemplate.convertAndSend(
                    TOPIC_PREFIX + lectureId,
                    SummaryStreamMessage.chunk(lectureId, "\n\n*[Generation stopped by user]*"));
        }

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

    /** MAP phase prompt: extract key information from a single chunk. */
    private String buildChunkPrompt(String chunkText, int chunkNumber, int totalChunks) {
        return """
                You are a fast document analyst.
                You are reading PART %d of %d of a document.

                Extract ONLY the most important information:
                - Main topic of this section (1 sentence)
                - Key points or findings (3-5 bullet points, each one sentence)
                - Any specific data, names, or figures
                - Any conclusions

                Be CONCISE. Limit to 150-200 words total. Do not repeat yourself.

                --- DOCUMENT SECTION %d/%d ---
                %s
                --- END SECTION ---
                """
                .formatted(chunkNumber, totalChunks, chunkNumber, totalChunks, chunkText);
    }

    /** REDUCE phase prompt: combine chunk summaries into final structured analysis. */
    private String buildReducePrompt(String combinedSummaries) {
        return """
                You are an expert document analyst.
                Below are summaries of different sections of a document.
                Your job is to combine them into ONE comprehensive, well-structured analysis.

                Eliminate redundancy. Merge overlapping points. Ensure nothing important is lost.

                Use EXACTLY these section markers on their own line. Start each section on a new line.
                Write thoroughly. Do not skip any section. If something is unclear, mention it.

                [TITLE]
                Write a short, descriptive title for this document.

                [MAIN_TOPIC]
                What is this document about and what is its goal? Write 3-5 sentences explaining the main topic, purpose, and scope.

                [KEY_POINTS]
                List ALL major points, findings, or arguments as bullet points starting with "- ". Each bullet must be a full sentence. Include at least 8 points.

                [IMPORTANT_DETAILS]
                List specific data, statistics, dates, names, or figures as bullet points starting with "- ". Be precise and factual. Include at least 6 details.

                [STRUCTURE_OVERVIEW]
                Briefly describe how the document is organized. Mention sections, chapters, or logical divisions. Write 2-4 sentences.

                [CONCLUSIONS]
                What conclusions or recommendations does the document present? List as bullet points starting with "- ". Include action items if any.

                [NOTABLE_QUOTES]
                Pull out critical or standout statements as bullet points starting with "- ". Quote closely to the original.

                [ADDITIONAL_NOTES]
                Flag anything unusual, important, or easily overlooked as bullet points starting with "- ". Mention unclear sections or gaps.

                --- SECTION SUMMARIES ---
                %s
                --- END ---
                """
                .formatted(combinedSummaries);
    }

    /** Single-pass prompt for short documents — full thorough analysis. */
    private String buildFinalPrompt(String lectureText) {
        return """
                You are an expert document analyst.
                Analyze the document content below thoroughly and provide a complete, detailed summary.

                Use EXACTLY these section markers on their own line. Start each section on a new line.
                Write thoroughly. Do not skip any section. If something is unclear, mention it.

                [TITLE]
                Write a short, descriptive title for this document.

                [MAIN_TOPIC]
                What is this document about and what is its goal? Write 3-5 sentences explaining the main topic, purpose, and scope.

                [KEY_POINTS]
                List ALL major points, findings, or arguments as bullet points starting with "- ". Each bullet must be a full sentence. Include at least 8 points.

                [IMPORTANT_DETAILS]
                List specific data, statistics, dates, names, or figures as bullet points starting with "- ". Be precise and factual. Include at least 6 details.

                [STRUCTURE_OVERVIEW]
                Briefly describe how the document is organized. Mention sections, chapters, or logical divisions. Write 2-4 sentences.

                [CONCLUSIONS]
                What conclusions or recommendations does the document present? List as bullet points starting with "- ". Include action items if any.

                [NOTABLE_QUOTES]
                Pull out critical or standout statements as bullet points starting with "- ". Quote closely to the original.

                [ADDITIONAL_NOTES]
                Flag anything unusual, important, or easily overlooked as bullet points starting with "- ". Mention unclear sections or gaps.

                --- DOCUMENT CONTENT ---
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
