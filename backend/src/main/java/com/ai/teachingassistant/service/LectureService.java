package com.ai.teachingassistant.service;

import com.ai.teachingassistant.client.PythonRagClient;
import com.ai.teachingassistant.dto.LectureHistoryResponse;
import com.ai.teachingassistant.dto.SummaryResponse;
import com.ai.teachingassistant.model.Lecture;
import com.ai.teachingassistant.repository.LectureRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * LectureService orchestrates the full processing workflow:
 * PDF extraction → AI summarization → persistence → structured response.
 *
 * Also provides history retrieval and ownership-checked deletion.
 *
 * <p>
 * <b>Caching:</b> Before calling the LLM, an MD5 hash of the raw PDF bytes
 * is computed. If an existing lecture with the same hash is found in the
 * database, the cached summary is returned immediately — the LLM is never
 * invoked, saving time and API cost.
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LectureService {

    private final PdfExtractionService pdfExtractionService;
    private final SummarizationService summarizationService;
    private final LectureRepository lectureRepository;
    private final ObjectMapper objectMapper;
    private final PythonRagClient pythonRagClient;

    // ── Stats helpers ─────────────────────────────────────────────────────

    public long countByUser(String userId) {
        return lectureRepository.countByUserId(userId);
    }

    public long sumPagesByUser(String userId) {
        return lectureRepository.sumPageCountByUserId(userId);
    }

    // ── Upload & Summarize ────────────────────────────────────────────────

    /**
     * Accepts an uploaded PDF, extracts text, generates an AI summary,
     * persists the lecture record to the DB, and returns the SummaryResponse.
     *
     * <p>
     * If the exact same PDF (same bytes) was uploaded before, the cached
     * result is returned instantly without hitting the LLM.
     * </p>
     */
    public SummaryResponse processLecture(MultipartFile file, String userId)
            throws IOException, InterruptedException {

        String fileName = file.getOriginalFilename() != null
                ? file.getOriginalFilename()
                : "unknown.pdf";

        log.info("Processing lecture: file='{}', size={}KB, user='{}'",
                fileName, file.getSize() / 1024, userId);
        long startTime = System.currentTimeMillis();

        // ── Step 1: compute content hash ────────────────────────────────
        byte[] pdfBytes = file.getBytes();
        String contentHash = computeMd5(pdfBytes);
        log.debug("PDF content hash: {}", contentHash);

        // ── Step 2: cache lookup ─────────────────────────────────────────
        Optional<Lecture> cached = lectureRepository.findFirstByContentHash(contentHash);
        if (cached.isPresent()) {
            Lecture cachedLecture = cached.get();
            log.info("Cache HIT for hash={} (file='{}') — skipping LLM call. elapsed={}ms",
                    contentHash, fileName, System.currentTimeMillis() - startTime);

            // If the cached lecture belongs to someone else, clone it for this user
            if (userId != null && cachedLecture.getUserId() != null
                    && !userId.equals(cachedLecture.getUserId())) {
                log.info("Cache HIT owned by different user — cloning lecture for user={}", userId);
                cachedLecture = cloneForUser(cachedLecture, userId, fileName);
            } else {
                // Claim orphaned lectures (uploaded before auth existed)
                claimIfOrphaned(cachedLecture, userId);
            }

            SummaryResponse cachedResponse = deserializeFromJson(cachedLecture.getSummary());
            cachedResponse.setLectureId(cachedLecture.getId());
            cachedResponse.setFromCache(true);
            cachedResponse.setFileName(fileName);

            // Re-index if vectors may be missing
            tryReindexIfNeeded(cachedLecture);

            return cachedResponse;
        }

        log.info("Cache MISS for hash={} — calling LLM.", contentHash);

        // ── Step 3: full pipeline (extract → summarise → persist) ────────
        String extractedText = pdfExtractionService.extractText(file);
        int pageCount = countPagesFromText(extractedText);

        SummaryResponse response = summarizationService.generateSummary(
                extractedText, fileName, pageCount);

        String summaryJson = serializeToJson(response);

        Lecture lecture = Lecture.builder()
                .id(UUID.randomUUID().toString())
                .fileName(fileName)
                .originalText(extractedText)
                .summary(summaryJson)
                .provider(response.getProvider())
                .fileSizeBytes(file.getSize())
                .pageCount(pageCount)
                .processedAt(LocalDateTime.now())
                .userId(userId)
                .contentHash(contentHash) // ← stored for future cache hits
                .build();

        lectureRepository.save(lecture);
        log.info("Lecture saved: id={}, pages={}, elapsed={}ms",
                lecture.getId(), pageCount, System.currentTimeMillis() - startTime);

        // ── Step 4: Index into Python-based Advanced RAG (Synchronous) ──
        try {
            log.info("Triggering advanced RAG indexing for lectureId={}...", lecture.getId());
            pythonRagClient.indexPdf(lecture.getId(), file).block();
            log.info("Advanced RAG indexing complete for lectureId={}", lecture.getId());
        } catch (Exception e) {
            log.error("Advanced RAG indexing failed for lectureId={}: {}", lecture.getId(), e.getMessage());
        }

        response.setLectureId(lecture.getId());
        response.setFromCache(false);

        return response;
    }

    /** Backward-compatible overload (no userId). */
    public SummaryResponse processLecture(MultipartFile file)
            throws IOException, InterruptedException {
        return processLecture(file, null);
    }

    // ── Smart Process (mode-aware) ────────────────────────────────────────

    /**
     * Unified entry-point for the Smart Upload flow.
     *
     * <ul>
     * <li><b>mode=summary</b> — full synchronous pipeline; returns a complete
     * {@link ProcessResponse} with the summary embedded.</li>
     * <li><b>mode=chat | mode=quiz</b> — extracts text + indexes chunks
     * synchronously (so RAG Q&amp;A is immediately available), then fires
     * async summarization in the background. Returns the lectureId
     * immediately so the user can start asking questions or taking the quiz
     * without waiting for the LLM.</li>
     * </ul>
     *
     * @param file   the uploaded PDF
     * @param userId authenticated user ID
     * @param mode   "summary" | "chat" | "quiz"
     */
    public com.ai.teachingassistant.dto.ProcessResponse processLectureWithMode(
            MultipartFile file, String userId, String mode)
            throws IOException, InterruptedException {

        // ── Summary mode: reuse the existing full pipeline ───────────────
        if ("summary".equalsIgnoreCase(mode)) {
            SummaryResponse summary = processLecture(file, userId);
            return com.ai.teachingassistant.dto.ProcessResponse.builder()
                    .lectureId(summary.getLectureId())
                    .mode(mode)
                    .status("complete")
                    .fileName(summary.getFileName())
                    .pageCount(summary.getPageCount())
                    .chunksIndexed(-1)
                    .summary(summary)
                    .build();
        }

        // ── Chat / Quiz mode: fast path — no blocking LLM call ───────────
        String fileName = file.getOriginalFilename() != null
                ? file.getOriginalFilename()
                : "unknown.pdf";

        log.info("Smart-process ({}) request: file='{}', user='{}'", mode, fileName, userId);
        long startTime = System.currentTimeMillis();

        byte[] pdfBytes = file.getBytes();
        String contentHash = computeMd5(pdfBytes);

        // Return existing lecture if same PDF was already processed
        Optional<Lecture> cached = lectureRepository.findFirstByContentHash(contentHash);
        if (cached.isPresent()) {
            Lecture c = cached.get();
            log.info("Smart-process cache HIT for hash={}, reusing lectureId={}", contentHash, c.getId());

            // If the cached lecture belongs to someone else, clone it for this user
            if (userId != null && c.getUserId() != null && !userId.equals(c.getUserId())) {
                log.info("Smart-process cache HIT owned by different user — cloning for user={}", userId);
                c = cloneForUser(c, userId, fileName);
            } else {
                claimIfOrphaned(c, userId);
            }

            // Fix Bug #3 — re-index if needed (case: cache hit)
            // Even if the lecture is cached, the vectors might be missing or under a different ID.
            // We call indexPdf here with the current file to be 100% sure the AI knows the content.
            try {
                log.info("Smart-process cache hit: re-indexing for lectureId={}...", c.getId());
                pythonRagClient.indexPdf(c.getId(), file).block();
            } catch (Exception e) {
                log.warn("Smart-process: Re-index on cache hit failed for lectureId={}: {}", c.getId(), e.getMessage());
            }

            return com.ai.teachingassistant.dto.ProcessResponse.builder()
                    .lectureId(c.getId())
                    .mode(mode)
                    .status("indexing_complete")
                    .fileName(fileName)
                    .pageCount(c.getPageCount())
                    .chunksIndexed(-1)
                    .build();
        }

        // Extract text from PDF
        String extractedText = pdfExtractionService.extractText(file);
        int pageCount = countPagesFromText(extractedText);

        // Save lecture record (summary is null — will be filled in async later)
        Lecture lecture = Lecture.builder()
                .id(UUID.randomUUID().toString())
                .fileName(fileName)
                .originalText(extractedText)
                .summary(null)
                .provider(null)
                .fileSizeBytes(file.getSize())
                .pageCount(pageCount)
                .processedAt(LocalDateTime.now())
                .userId(userId)
                .contentHash(contentHash)
                .build();
        lectureRepository.save(lecture);
        log.info("Smart-process lecture saved: id={}, pages={}", lecture.getId(), pageCount);

        // Index in Python microservice (Synchronous to ensure documents table is populated)
        try {
            log.info("Smart-process: Triggering RAG indexing for lectureId={}...", lecture.getId());
            pythonRagClient.indexPdf(lecture.getId(), file).block();
            log.info("Smart-process: RAG indexing complete for lectureId={}", lecture.getId());
        } catch (Exception e) {
            log.error("Smart-process indexing failed for lectureId={}: {}", lecture.getId(), e.getMessage());
        }

        // NOTE: No async summarization here — the frontend triggers streaming
        // summarization via POST /api/lecture/{id}/summarize-stream after
        // connecting to the WebSocket. This avoids double LLM calls and ensures
        // the user sees real-time streaming text.

        log.info("Smart-process ({}) returned in {}ms for lectureId={}",
                mode, System.currentTimeMillis() - startTime, lecture.getId());

        return com.ai.teachingassistant.dto.ProcessResponse.builder()
                .lectureId(lecture.getId())
                .mode(mode)
                .status("indexing_complete")
                .fileName(fileName)
                .pageCount(pageCount)
                .chunksIndexed(-1)
                .build();
    }

    /**
     * "Quick index" mode: extract PDF text, index into pgvector for RAG,
     * save a minimal Lecture record — but do NOT call the LLM for summarization.
     *
     * <p>
     * On success the lectureId can immediately be used for Q&amp;A and quiz
     * generation.
     * </p>
     *
     * @param file   the uploaded PDF
     * @param userId the authenticated user's ID
     * @return a {@link com.ai.teachingassistant.dto.QuickIndexResponse} with
     *         lectureId + stats
     */
    public com.ai.teachingassistant.dto.QuickIndexResponse indexLectureOnly(
            MultipartFile file, String userId) throws IOException {

        String fileName = file.getOriginalFilename() != null
                ? file.getOriginalFilename()
                : "unknown.pdf";

        log.info("Quick-index request: file='{}', size={}KB, user='{}'",
                fileName, file.getSize() / 1024, userId);
        long startTime = System.currentTimeMillis();

        // ── Step 1: compute hash — return cached lectureId if same PDF ───
        byte[] pdfBytes = file.getBytes();
        String contentHash = computeMd5(pdfBytes);

        java.util.Optional<Lecture> cached = lectureRepository.findFirstByContentHash(contentHash);
        if (cached.isPresent()) {
            Lecture c = cached.get();
            log.info("Quick-index cache HIT for hash={} — reusing lectureId={}", contentHash, c.getId());

            // Claim orphaned lectures
            claimIfOrphaned(c, userId);

            // Re-index if needed
            int chunksIndexed = tryReindexIfNeeded(c);

            return com.ai.teachingassistant.dto.QuickIndexResponse.builder()
                    .lectureId(c.getId())
                    .fileName(fileName)
                    .pageCount(c.getPageCount())
                    .chunksIndexed(chunksIndexed)
                    .mode("quick_index")
                    .build();
        }

        // ── Step 2: extract PDF text ─────────────────────────────────────
        String extractedText = pdfExtractionService.extractText(file);
        int pageCount = countPagesFromText(extractedText);

        // ── Step 3: save a minimal lecture record (no summary) ───────────
        Lecture lecture = Lecture.builder()
                .id(java.util.UUID.randomUUID().toString())
                .fileName(fileName)
                .originalText(extractedText)
                .summary(null) // no summary generated
                .provider(null)
                .fileSizeBytes(file.getSize())
                .pageCount(pageCount)
                .processedAt(LocalDateTime.now())
                .userId(userId)
                .contentHash(contentHash)
                .build();

        lectureRepository.save(lecture);
        log.info("Quick-index lecture saved: id={}, pages={}", lecture.getId(), pageCount);

        // Fix Bug #1 — Index in Python microservice using lecture.getId() (Synchronous)
        try {
            log.info("Quick-index: Triggering RAG indexing for lectureId={}...", lecture.getId());
            pythonRagClient.indexPdf(lecture.getId(), file).block();
            log.info("Quick-index: RAG indexing complete for lectureId={}", lecture.getId());
        } catch (Exception e) {
            log.error("Quick-index: Advanced RAG indexing failed for lectureId={}: {}", lecture.getId(), e.getMessage());
        }

        log.info("Quick-index complete: id={}, elapsed={}ms",
                lecture.getId(), System.currentTimeMillis() - startTime);

        return com.ai.teachingassistant.dto.QuickIndexResponse.builder()
                .lectureId(lecture.getId())
                .fileName(fileName)
                .pageCount(pageCount)
                .chunksIndexed(-1)
                .mode("quick_index")
                .build();
    }

    /**
     * Returns all lectures for a user as lightweight DTOs (no originalText).
     */
    public List<LectureHistoryResponse> getLectureHistory(String userId) {
        return lectureRepository
                .findByUserIdOrderByProcessedAtDesc(userId)
                .stream()
                .map(LectureHistoryResponse::from)
                .collect(Collectors.toList());
    }

    /**
     * Returns a single lecture by ID, enforcing ownership.
     *
     * @throws ResponseStatusException 404 if not found, 403 if wrong owner.
     */
    public Lecture getLectureById(String id, String userId) {
        Lecture lecture = lectureRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Lecture not found: " + id));

        // Allow access if: (a) no userId given, (b) matches owner, or
        // (c) lecture has no owner (legacy data from before auth existed)
        if (userId != null
                && lecture.getUserId() != null
                && !userId.equals(lecture.getUserId())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Access denied to lecture: " + id);
        }

        // Claim orphaned lectures for the requesting user
        claimIfOrphaned(lecture, userId);

        return lecture;
    }

    /**
     * Deletes a lecture by ID after verifying ownership.
     *
     * @throws ResponseStatusException 404 if not found, 403 if wrong owner.
     */
    public void deleteLecture(String id, String userId) {
        Lecture lecture = getLectureById(id, userId);
        lectureRepository.delete(lecture);
        log.info("Lecture deleted: id={} by user='{}'", id, userId);
    }

    // ── Re-index ──────────────────────────────────────────────────────────

    /**
     * Re-indexes a lecture's stored text into the pgvector store.
     * Used to recover lectures whose RAG indexing failed silently during upload
     * (e.g. because the embedding model was not yet installed at that time).
     *
     * @throws ResponseStatusException 404 if not found, 403 if wrong owner.
     */
    // Fix Bug #4 — Working re-index endpoint that takes the original PDF file
    public void reindexLectureWithFile(String lectureId, MultipartFile file, String userId) {
        // 1. Verify Ownership
        getLectureById(lectureId, userId);
        
        // 2. Trigger Python Indexing
        log.info("Manual re-index: Triggering RAG indexing for lectureId={}...", lectureId);
        pythonRagClient.indexPdf(lectureId, file).block();
        log.info("Manual re-index: RAG indexing complete for lectureId={}", lectureId);
    }

    // ── Ownership & re-indexing helpers ────────────────────────────────────

    /**
     * If this lecture has no owner (legacy upload), assign it to the given user.
     */
    private void claimIfOrphaned(Lecture lecture, String userId) {
        if (userId != null && lecture.getUserId() == null) {
            lecture.setUserId(userId);
            lectureRepository.save(lecture);
            log.info("Claimed orphaned lecture {} for user={}", lecture.getId(), userId);
        }
    }

    /**
     * Creates a new Lecture record for the given user, cloned from an existing
     * cached lecture. This lets multiple users benefit from the same PDF extraction
     * and summary without sharing a single owner-locked record.
     */
    private Lecture cloneForUser(Lecture source, String userId, String fileName) {
        Lecture clone = Lecture.builder()
                .id(UUID.randomUUID().toString())
                .fileName(fileName)
                .originalText(source.getOriginalText())
                .summary(source.getSummary())
                .provider(source.getProvider())
                .fileSizeBytes(source.getFileSizeBytes())
                .pageCount(source.getPageCount())
                .processedAt(LocalDateTime.now())
                .userId(userId)
                .contentHash(source.getContentHash())
                .build();
        lectureRepository.save(clone);
        log.info("Cloned lecture {} -> {} for user={}", source.getId(), clone.getId(), userId);
        return clone;
    }

    /**
     * Re-indexes a lecture's text into the vector store if it has stored text.
     * Returns the number of chunks indexed (0 if skipped or failed).
     */
    /**
     * Attempts to trigger a re-index. Note: Python RAG expects the File.
     * Since we only have the text in an orphaned lecture, we skip auto-reindex for now.
     */
    private int tryReindexIfNeeded(Lecture lecture) {
        log.info("Advanced RAG: Skipping automatic text-based re-index for lectureId={}. "
                + "Upload the PDF again to refresh the vector store if needed.", lecture.getId());
        return 0;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Computes a lowercase hex MD5 hash of the given bytes.
     * MD5 is used purely as a fast content fingerprint — not for any
     * security-sensitive purpose.
     */
    private String computeMd5(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // MD5 is mandatory in every Java SE runtime — this branch is unreachable
            throw new IllegalStateException("MD5 algorithm not available", e);
        }
    }

    private int countPagesFromText(String text) {
        long formFeeds = text.chars().filter(c -> c == '\f').count();
        return (int) (formFeeds > 0 ? formFeeds + 1 : 1);
    }

    private String serializeToJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize summary to JSON: {}", e.getMessage());
            return "{}";
        }
    }

    private SummaryResponse deserializeFromJson(String json) {
        try {
            return objectMapper.readValue(json, SummaryResponse.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize cached summary JSON: {}", e.getMessage());
            return SummaryResponse.builder().build();
        }
    }
}