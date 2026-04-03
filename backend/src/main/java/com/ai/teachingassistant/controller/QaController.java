package com.ai.teachingassistant.controller;

import com.ai.teachingassistant.client.PythonRagClient;
import com.ai.teachingassistant.dto.AskQuestionRequest;
import com.ai.teachingassistant.dto.AskQuestionResponse;
import com.ai.teachingassistant.service.LectureService;
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
            PythonRagClient.QueryResponse ragResponse =
                    pythonRagClient.query(request.getQuestion(), lectureId).block();

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
                            ? ragResponse.getChunks().size() : 0)
                    .build());

        } catch (Exception e) {
            log.error("Q&A failed for lectureId={}: {}", lectureId, e.getMessage(), e);

            // Fallback: answer without RAG using lecture text directly
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of(
                            "error", "AI service error: " + e.getMessage(),
                            "hint", "Make sure the Python RAG service is running on port 8001"));
        }
    }
}
