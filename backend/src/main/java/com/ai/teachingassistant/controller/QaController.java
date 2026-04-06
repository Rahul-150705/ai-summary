package com.ai.teachingassistant.controller;

import com.ai.teachingassistant.client.PythonRagClient;
import com.ai.teachingassistant.dto.AskQuestionRequest;
import com.ai.teachingassistant.dto.AskQuestionResponse;
import com.ai.teachingassistant.service.LectureService;
import com.ai.teachingassistant.service.StreamingQaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/lecture")
@RequiredArgsConstructor
public class QaController {

        private final LectureService lectureService;
        private final PythonRagClient pythonRagClient;
        private final StreamingQaService streamingQaService;

        // ── POST /api/lecture/{lectureId}/ask (blocking — kept for backwards compat)
        // ──

        @PostMapping("/{lectureId}/ask")
        public ResponseEntity<?> askQuestion(
                        @PathVariable String lectureId,
                        @Valid @RequestBody AskQuestionRequest request,
                        Principal principal) {

                if (principal == null) {
                        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                        .body(Map.of("error", "Not authenticated"));
                }

                String userId = principal.getName();
                log.info("Q&A request: lectureId={}, user={}, question='{}'",
                                lectureId, userId, request.getQuestion());

                // Verify ownership
                lectureService.getLectureById(lectureId, userId);

                try {
                        PythonRagClient.QueryResponse ragResponse = pythonRagClient
                                        .query(request.getQuestion(), lectureId).block();

                        if (ragResponse == null) {
                                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                                .body(Map.of("error", "RAG service unavailable. Please try again."));
                        }

                        log.info("Q&A answered for lectureId={}, chunks used={}",
                                        lectureId,
                                        ragResponse.getChunks() != null ? ragResponse.getChunks().size() : 0);

                        return ResponseEntity.ok(AskQuestionResponse.builder()
                                        .lectureId(lectureId)
                                        .question(request.getQuestion())
                                        .answer(ragResponse.getAnswer())
                                        .sourceChunks(ragResponse.getChunks())
                                        .chunksUsed(ragResponse.getChunks() != null
                                                        ? ragResponse.getChunks().size()
                                                        : 0)
                                        .build());

                } catch (Exception e) {
                        log.error("Q&A failed for lectureId={}: {}", lectureId, e.getMessage(), e);

                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                        .body(Map.of(
                                                        "error", "AI service error: " + e.getMessage(),
                                                        "hint",
                                                        "Make sure the Python RAG service is running on port 8001"));
                }
        }

        // ── POST /api/lecture/{lectureId}/ask-stream (streaming via WebSocket) ──

        /**
         * Triggers a streaming Q&A answer over WebSocket (STOMP).
         *
         * <p>
         * Flow:
         * <ol>
         * <li>Client subscribes to {@code /topic/lectures/{lectureId}/qa} BEFORE
         * calling this.</li>
         * <li>This endpoint validates ownership and returns <b>202 ACCEPTED</b>
         * immediately.</li>
         * <li>Background thread retrieves RAG context from Python, then streams Ollama
         * tokens to the WebSocket topic as {@code ANSWER_CHUNK} messages.</li>
         * <li>When done, {@code ANSWER_COMPLETED} is sent with the full answer and
         * source chunks.</li>
         * <li>On error, {@code ANSWER_ERROR} is sent.</li>
         * </ol>
         *
         * <p>
         * Message types on {@code /topic/lectures/{lectureId}}:
         * 
         * <pre>
         * ANSWER_CHUNK     → chunk (streaming token)
         * ANSWER_COMPLETED → fullAnswer, sourceChunks, chunksUsed
         * ANSWER_ERROR     → error (human-readable message)
         * </pre>
         */
        @PostMapping("/{lectureId}/ask-stream")
        public ResponseEntity<?> askQuestionStream(
                        @PathVariable String lectureId,
                        @Valid @RequestBody AskQuestionRequest request,
                        Principal principal) {

                if (principal == null) {
                        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                        .body(Map.of("error", "Not authenticated"));
                }

                String userId = principal.getName();
                log.info("Streaming Q&A request: lectureId={}, user={}, question='{}'",
                                lectureId, userId, request.getQuestion());

                // Verify ownership (throws 404/403 if invalid)
                lectureService.getLectureById(lectureId, userId);

                // Dispatch async streaming — returns immediately
                streamingQaService.streamAnswer(lectureId, request.getQuestion());

                // 202 ACCEPTED — answer tokens will arrive via WebSocket
                return ResponseEntity.accepted().body(Map.of(
                                "status", "streaming_started",
                                "lectureId", lectureId,
                                "message", "Subscribe to /topic/qa/" + lectureId + " for real-time answer chunks."));
        }
}