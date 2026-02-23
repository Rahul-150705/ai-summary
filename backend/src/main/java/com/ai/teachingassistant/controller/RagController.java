package com.ai.teachingassistant.controller;

import com.ai.teachingassistant.dto.AskQuestionRequest;
import com.ai.teachingassistant.dto.AskQuestionResponse;
import com.ai.teachingassistant.service.RagService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;

/**
 * RagController exposes the RAG-powered Q&amp;A endpoint.
 *
 * <pre>
 *   POST /api/lecture/{lectureId}/ask
 *   Body: { "question": "What is the main idea of this lecture?" }
 * </pre>
 *
 * <p>
 * The lecture must have been previously uploaded and indexed.
 * The student can only ask questions about a specific lecture at a time.
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/lecture")
@RequiredArgsConstructor
@CrossOrigin(origins = { "http://localhost:5173", "http://localhost:3000" })
public class RagController {

    private final RagService ragService;

    /**
     * Answers a student question using the RAG pipeline:
     * <ol>
     * <li>Embeds the question.</li>
     * <li>Retrieves the top-K most relevant chunks from the lecture
     * (pgvector).</li>
     * <li>Passes chunks + question to the LLM.</li>
     * <li>Returns the grounded answer.</li>
     * </ol>
     *
     * @param lectureId the ID of the lecture to query
     * @param request   the question body
     * @param principal the authenticated user (Spring Security)
     * @return {@link AskQuestionResponse} with the answer and source chunks
     */
    @PostMapping("/{lectureId}/ask")
    public ResponseEntity<?> askQuestion(
            @PathVariable String lectureId,
            @Valid @RequestBody AskQuestionRequest request,
            Principal principal) {

        String userId = (principal != null) ? principal.getName() : "anonymous";
        log.info("Q&A request: lectureId={}, user='{}', question='{}'",
                lectureId, userId, request.getQuestion());

        try {
            AskQuestionResponse response = ragService.askQuestion(lectureId, request.getQuestion());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("RAG Q&A failed for lectureId={}: {}", lectureId, e.getMessage(), e);
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to answer your question. Please try again."));
        }
    }
}
