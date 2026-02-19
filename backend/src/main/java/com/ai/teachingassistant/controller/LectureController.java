package com.ai.teachingassistant.controller;

import com.ai.teachingassistant.dto.SummaryResponse;
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
import java.util.Map;

/**
 * LectureController exposes REST endpoints for PDF upload and summarization.
 * Handles CORS, input validation, and delegates processing to LectureService.
 */
@Slf4j
@RestController
@RequestMapping("/api/lecture")
@RequiredArgsConstructor
@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:3000"})
public class LectureController {

    private final LectureService lectureService;

    @Value("${llm.provider:openai}")
    private String activeProvider;

    /**
     * POST /api/lecture/summarize
     * Accepts a PDF file upload and returns an AI-generated structured summary.
     *
     * @param file  The PDF file from multipart/form-data request.
     * @return      SummaryResponse JSON with title, keyPoints, definitions, examPoints.
     */
    @PostMapping(value = "/summarize", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> summarizeLecture(@RequestParam("file") MultipartFile file) {

        log.info("Received summarize request: file='{}', size={}KB",
                file.getOriginalFilename(), file.getSize() / 1024);

        // Input validation
        if (file.isEmpty()) {
            log.warn("Empty file received");
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Uploaded file is empty. Please select a valid PDF."));
        }

        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".pdf")) {
            log.warn("Invalid file type: {}", filename);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Only PDF files are accepted. Received: " + filename));
        }

        long maxSizeBytes = 10L * 1024 * 1024;
        if (file.getSize() > maxSizeBytes) {
            log.warn("File too large: {}KB", file.getSize() / 1024);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "File size exceeds 10MB limit."));
        }

        try {
            SummaryResponse response = lectureService.processLecture(file);
            log.info("Successfully generated summary for: {}", filename);
            return ResponseEntity.ok(response);

        } catch (IOException e) {
            log.error("PDF processing error for file '{}': {}", filename, e.getMessage());
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(Map.of("error", "PDF processing failed: " + e.getMessage()));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("LLM API call interrupted for file '{}'", filename);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "AI service request was interrupted. Please try again."));

        } catch (Exception e) {
            log.error("Unexpected error processing file '{}': {}", filename, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "An unexpected error occurred. Please try again."));
        }
    }

    /**
     * GET /api/lecture/health
     * Health check endpoint for monitoring and readiness probes.
     *
     * @return Service status information.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> healthCheck() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "AI Teaching Assistant",
                "provider", activeProvider
        ));
    }
}