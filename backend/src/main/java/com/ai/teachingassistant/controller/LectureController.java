package com.ai.teachingassistant.controller;

import com.ai.teachingassistant.dto.LectureHistoryResponse;
import com.ai.teachingassistant.dto.SummaryResponse;
import com.ai.teachingassistant.dto.UserStatsResponse;
import com.ai.teachingassistant.model.Lecture;
import com.ai.teachingassistant.model.QuizAttempt;
import com.ai.teachingassistant.repository.QuizAttemptRepository;
import com.ai.teachingassistant.service.LectureService;
import com.ai.teachingassistant.service.StreamingSummarizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.Principal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * LectureController exposes REST endpoints for PDF upload, summarization,
 * lecture history, and deletion.
 */
@Slf4j
@RestController
@RequestMapping("/api/lecture")
@RequiredArgsConstructor
@CrossOrigin(origins = { "http://localhost:5173", "http://localhost:3000" })
public class LectureController {

    private final LectureService lectureService;
    private final QuizAttemptRepository quizAttemptRepository;
    private final StreamingSummarizationService streamingSummarizationService;

    @Value("${llm.provider:openai}")
    private String activeProvider;

    // ── GET /api/user/stats ───────────────────────────────────────────────

    @GetMapping("/stats")
    public ResponseEntity<?> getUserStats(Principal principal) {
        if (principal == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not authenticated"));

        String userId = principal.getName();
        long totalLectures = lectureService.countByUser(userId);
        long totalPages = lectureService.sumPagesByUser(userId);
        long totalQuizzes = quizAttemptRepository.countByUserId(userId);
        int avgScore = (int) Math.round(quizAttemptRepository.averagePercentageByUserId(userId));
        long studyDays = quizAttemptRepository.countActiveDaysSince(userId, LocalDateTime.now().minusDays(30));

        List<QuizAttempt> recent = quizAttemptRepository.findByUserIdOrderByAttemptedAtDesc(userId)
                .stream().limit(10).collect(Collectors.toList());

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd MMM, HH:mm");
        List<UserStatsResponse.QuizAttemptSummary> recentDtos = recent.stream()
                .map(a -> UserStatsResponse.QuizAttemptSummary.builder()
                        .lectureFileName(a.getLectureFileName())
                        .percentage(a.getPercentage())
                        .grade(a.getGrade())
                        .attemptedAt(a.getAttemptedAt() != null ? a.getAttemptedAt().format(fmt) : "")
                        .build())
                .collect(Collectors.toList());

        return ResponseEntity.ok(UserStatsResponse.builder()
                .totalLectures(totalLectures)
                .totalPagesProcessed(totalPages)
                .totalQuizAttempts(totalQuizzes)
                .averageQuizScore(avgScore)
                .studyDaysThisMonth(studyDays)
                .recentQuizzes(recentDtos)
                .build());
    }

    @PostMapping(value = "/summarize", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> summarizeLecture(
            @RequestParam("file") MultipartFile file,
            Principal principal) {

        log.info("Received summarize request: file='{}', size={}KB",
                file.getOriginalFilename(), file.getSize() / 1024);

        if (file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Uploaded file is empty. Please select a valid PDF."));
        }

        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".pdf")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Only PDF files are accepted. Received: " + filename));
        }

        if (file.getSize() > 10L * 1024 * 1024) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "File size exceeds 10MB limit."));
        }

        String userId = (principal != null) ? principal.getName() : null;

        try {
            SummaryResponse response = lectureService.processLecture(file, userId);
            log.info("Successfully generated summary for: {}", filename);
            return ResponseEntity.ok(response);

        } catch (IOException e) {
            log.error("PDF processing error for '{}': {}", filename, e.getMessage());
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(Map.of("error", "PDF processing failed: " + e.getMessage()));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "AI service request was interrupted. Please try again."));

        } catch (Exception e) {
            log.error("Unexpected error for '{}': {}", filename, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "An unexpected error occurred. Please try again."));
        }
    }

    // ── POST /api/lecture/process (Smart Upload — mode-aware) ────────────

    /**
     * Smart Upload endpoint. Accepts a PDF and a processing mode:
     * <ul>
     * <li><b>summary</b> — full sync pipeline → returns complete summary +
     * lectureId.</li>
     * <li><b>chat</b> — extract + index sync (fast) + async summarize → returns
     * lectureId immediately.</li>
     * <li><b>quiz</b> — same as chat.</li>
     * </ul>
     *
     * @param file uploaded PDF file
     * @param mode "summary" | "chat" | "quiz" (query param)
     */
    @PostMapping(value = "/process", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> processLecture(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "mode", defaultValue = "summary") String mode,
            Principal principal) {

        log.info("Smart-process request: mode='{}', file='{}', size={}KB",
                mode, file.getOriginalFilename(), file.getSize() / 1024);

        if (file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Uploaded file is empty. Please select a valid PDF."));
        }

        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".pdf")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Only PDF files are accepted."));
        }

        if (file.getSize() > 10L * 1024 * 1024) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "File size exceeds 10MB limit."));
        }

        if (!List.of("summary", "chat", "quiz").contains(mode.toLowerCase())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid mode. Use: summary, chat, or quiz."));
        }

        String userId = (principal != null) ? principal.getName() : null;

        try {
            var response = lectureService.processLectureWithMode(file, userId, mode.toLowerCase());
            log.info("Smart-process done: mode={}, lectureId={}, status={}",
                    mode, response.getLectureId(), response.getStatus());
            return ResponseEntity.ok(response);

        } catch (IOException e) {
            log.error("PDF error in smart-process for '{}': {}", filename, e.getMessage());
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(Map.of("error", "PDF processing failed: " + e.getMessage()));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Service interrupted. Please try again."));

        } catch (Exception e) {
            log.error("Unexpected error in smart-process for '{}': {}", filename, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "An unexpected error occurred. Please try again."));
        }
    }

    // ── POST /api/lecture/index (Quick Mode — no summary) ─────────────────

    /**
     * Indexes a PDF into the RAG vector store WITHOUT generating an AI summary.
     * This is significantly faster than /summarize (no LLM call).
     * The returned lectureId can immediately be used for Q&A and quiz generation.
     */
    @PostMapping(value = "/index", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> quickIndexLecture(
            @RequestParam("file") MultipartFile file,
            Principal principal) {

        log.info("Quick-index request: file='{}', size={}KB",
                file.getOriginalFilename(), file.getSize() / 1024);

        if (file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Uploaded file is empty. Please select a valid PDF."));
        }

        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".pdf")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Only PDF files are accepted. Received: " + filename));
        }

        if (file.getSize() > 10L * 1024 * 1024) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "File size exceeds 10MB limit."));
        }

        String userId = (principal != null) ? principal.getName() : null;

        try {
            var response = lectureService.indexLectureOnly(file, userId);
            log.info("Quick-index complete for: {}, lectureId={}", filename, response.getLectureId());
            return ResponseEntity.ok(response);

        } catch (IOException e) {
            log.error("PDF processing error during quick-index for '{}': {}", filename, e.getMessage());
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(Map.of("error", "PDF processing failed: " + e.getMessage()));

        } catch (Exception e) {
            log.error("Unexpected error during quick-index for '{}': {}", filename, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "An unexpected error occurred. Please try again."));
        }
    }

    // ── GET /api/lecture/history ──────────────────────────────────────────

    /**
     * Returns all lectures for the authenticated user (newest first).
     * Uses lightweight DTOs — no originalText in the response.
     */
    @GetMapping("/history")
    public ResponseEntity<List<LectureHistoryResponse>> getLectureHistory(Principal principal) {
        String userId = principal.getName();
        log.info("Fetching lecture history for user: {}", userId);
        List<LectureHistoryResponse> history = lectureService.getLectureHistory(userId);
        return ResponseEntity.ok(history);
    }

    // ── GET /api/lecture/{id} ─────────────────────────────────────────────

    /**
     * Returns a single lecture (including summary) by ID.
     * Returns 403 if the lecture belongs to another user.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Lecture> getLectureById(
            @PathVariable String id,
            Principal principal) {
        String userId = principal.getName();
        Lecture lecture = lectureService.getLectureById(id, userId);
        return ResponseEntity.ok(lecture);
    }

    // ── DELETE /api/lecture/{id} ──────────────────────────────────────────

    /**
     * Deletes a lecture by ID. Only the owner can delete their lecture.
     * Returns 204 No Content on success.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteLecture(
            @PathVariable String id,
            Principal principal) {
        String userId = principal.getName();
        log.info("Delete request for lecture id={} by user={}", id, userId);
        lectureService.deleteLecture(id, userId);
        return ResponseEntity.noContent().build();
    }

    // ── POST /api/lecture/{id}/reindex ────────────────────────────────────

    /**
     * Re-indexes a lecture's text into the pgvector RAG store.
     * Call this when Q&A returns "couldn't find relevant content" for a lecture
     * that was uploaded before the embedding model was available.
     */
    @PostMapping("/{id}/reindex")
    public ResponseEntity<Map<String, String>> reindexLecture(
            @PathVariable String id,
            Principal principal) {
        String userId = principal.getName();
        log.info("Re-index request for lecture id={} by user={}", id, userId);
        lectureService.reindexLecture(id, userId);
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Lecture re-indexed successfully. Q&A is now available."));
    }

    // ── POST /api/lecture/{id}/summarize-stream ────────────────────────────

    /**
     * Triggers real-time streaming summarization over WebSocket.
     *
     * <p>
     * How it works:
     * <ol>
     * <li>Client calls POST /api/lecture/{id}/summarize-stream</li>
     * <li>Server validates ownership, returns 202 ACCEPTED immediately.</li>
     * <li>Background thread calls Ollama with stream=true via WebClient.</li>
     * <li>Each text chunk is pushed to /topic/lectures/{id} as SUMMARY_CHUNK.</li>
     * <li>When complete, SUMMARY_COMPLETED is sent with the full text.</li>
     * <li>On error, SUMMARY_ERROR is sent.</li>
     * </ol>
     *
     * <p>
     * The React client must subscribe to /topic/lectures/{id} BEFORE
     * calling this endpoint. After receiving SUMMARY_COMPLETED, the summary
     * is also persisted in the DB and can be fetched via GET /api/lecture/{id}.
     */
    @PostMapping("/{id}/summarize-stream")
    public ResponseEntity<Map<String, String>> streamSummarize(
            @PathVariable String id,
            Principal principal) {

        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Not authenticated"));
        }

        String userId = principal.getName();
        log.info("Stream-summarize request for lectureId={} by user={}", id, userId);

        // Verify ownership (throws 404/403 if invalid)
        Lecture lecture = lectureService.getLectureById(id, userId);

        // Check there's text to summarize
        if (lecture.getOriginalText() == null || lecture.getOriginalText().isBlank()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(Map.of("error", "No extracted text available for this lecture."));
        }

        // Dispatch async streaming — returns immediately
        streamingSummarizationService.streamSummarization(id);

        // 202 ACCEPTED: "I've started processing, results will arrive via WebSocket"
        return ResponseEntity.accepted()
                .body(Map.of(
                        "status", "streaming_started",
                        "lectureId", id,
                        "message", "Subscribe to /topic/lectures/" + id + " for real-time chunks."));
    }

    // ── GET /api/lecture/health ───────────────────────────────────────────

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> healthCheck() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "AI Teaching Assistant",
                "provider", activeProvider));
    }
}