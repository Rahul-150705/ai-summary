package com.ai.teachingassistant.service;

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
    private final RagService ragService;

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
            log.info("Cache HIT for hash={} (file='{}') — skipping LLM call. elapsed={}ms",
                    contentHash, fileName, System.currentTimeMillis() - startTime);
            SummaryResponse cachedResponse = deserializeFromJson(cached.get().getSummary());
            // Attach the original lecture ID so quiz generation still works
            cachedResponse.setLectureId(cached.get().getId());
            cachedResponse.setFromCache(true);
            // Reflect the new filename in case user renamed the PDF
            cachedResponse.setFileName(fileName);
            // Note: vectors are already indexed from the first upload — no re-indexing
            // needed
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

        // ── Step 4: index chunks into pgvector for RAG Q&A ───────────────
        // Run after DB save so lectureId is guaranteed to exist.
        // Cache hits above already have their vectors stored — skip them.
        try {
            ragService.indexLecture(lecture.getId(), extractedText);
        } catch (Exception e) {
            // Indexing failure is non-fatal: summary is still returned correctly,
            // but Q&A will not work for this lecture until re-indexed.
            log.error("RAG indexing failed for lectureId={}: {}. "
                    + "Q&A will be unavailable for this lecture.",
                    lecture.getId(), e.getMessage(), e);
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

    // ── History ───────────────────────────────────────────────────────────

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

        if (userId != null && !userId.equals(lecture.getUserId())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Access denied to lecture: " + id);
        }
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
    public void reindexLecture(String lectureId, String userId) {
        Lecture lecture = getLectureById(lectureId, userId);

        if (lecture.getOriginalText() == null || lecture.getOriginalText().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "No stored text available for lecture: " + lectureId);
        }

        log.info("Re-indexing lecture: id={}, user={}", lectureId, userId);
        ragService.indexLecture(lectureId, lecture.getOriginalText());
        log.info("Re-indexing complete for lectureId={}", lectureId);
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