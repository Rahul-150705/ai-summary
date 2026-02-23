package com.ai.teachingassistant.controller;

import com.ai.teachingassistant.dto.LectureHistoryResponse;
import com.ai.teachingassistant.dto.SummaryResponse;
import com.ai.teachingassistant.model.Lecture;
import com.ai.teachingassistant.service.LectureService;
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
import java.util.List;
import java.util.Map;

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

    @Value("${llm.provider:openai}")
    private String activeProvider;

    // ── POST /api/lecture/summarize ───────────────────────────────────────

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

    // ── GET /api/lecture/health ───────────────────────────────────────────

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> healthCheck() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "AI Teaching Assistant",
                "provider", activeProvider));
    }
}