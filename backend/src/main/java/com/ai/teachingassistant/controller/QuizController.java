package com.ai.teachingassistant.controller;

import com.ai.teachingassistant.dto.QuizQuestion;
import com.ai.teachingassistant.dto.QuizResponse;
import com.ai.teachingassistant.dto.QuizSubmitRequest;
import com.ai.teachingassistant.dto.QuizSubmitResponse;
import com.ai.teachingassistant.service.QuizService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * QuizController exposes REST endpoints for quiz generation and answer
 * submission.
 *
 * POST /api/quiz/{lectureId}/generate → generate MCQ quiz for a lecture
 * POST /api/quiz/{lectureId}/submit → submit answers and get score +
 * per-question result
 */
@Slf4j
@RestController
@RequestMapping("/api/quiz")
@RequiredArgsConstructor
@CrossOrigin(origins = { "http://localhost:5173", "http://localhost:3000" })
public class QuizController {

    private final QuizService quizService;

    /**
     * In-memory cache: lectureId → list of generated questions.
     * Used to validate answers on submission without re-calling the LLM.
     */
    private final ConcurrentHashMap<String, List<QuizQuestion>> quizCache = new ConcurrentHashMap<>();

    // ── POST /api/quiz/{lectureId}/generate ──────────────────────────────────

    /**
     * Generates a fresh MCQ quiz for the given lecture.
     *
     * @param lectureId    The lecture to quiz on.
     * @param numQuestions Number of questions to generate (default 10, max 20).
     * @param principal    Authenticated user.
     */
    @PostMapping("/{lectureId}/generate")
    public ResponseEntity<?> generateQuiz(
            @PathVariable String lectureId,
            @RequestParam(defaultValue = "10") int numQuestions,
            Principal principal) {

        if (numQuestions < 1)
            numQuestions = 5;
        if (numQuestions > 20)
            numQuestions = 20;

        String userId = (principal != null) ? principal.getName() : null;

        try {
            QuizResponse quiz = quizService.generateQuiz(lectureId, userId, numQuestions);

            // Cache questions so /submit can grade them
            quizCache.put(lectureId, quiz.getQuestions());

            return ResponseEntity.ok(quiz);

        } catch (Exception e) {
            log.error("Quiz generation failed for lectureId={}: {}", lectureId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Quiz generation failed: " + e.getMessage()));
        }
    }

    // ── POST /api/quiz/{lectureId}/submit ────────────────────────────────────

    /**
     * Accepts the user's answers and returns a graded result.
     *
     * Request body:
     * {
     * "answers": ["A", "C", "B", ...] // one letter per question, in order
     * }
     *
     * Response:
     * {
     * "score": 7,
     * "totalQuestions": 10,
     * "percentage": 70,
     * "grade": "Good 👍",
     * "results": [
     * { "index": 0, "question": "...", "selectedAnswer": "A",
     * "correctAnswer": "C", "correct": false, "explanation": "..." },
     * ...
     * ]
     * }
     */
    @PostMapping("/{lectureId}/submit")
    public ResponseEntity<?> submitQuiz(
            @PathVariable String lectureId,
            @RequestBody QuizSubmitRequest submitRequest,
            Principal principal) {

        List<QuizQuestion> questions = quizCache.get(lectureId);

        if (questions == null || questions.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error",
                            "No quiz found for this lecture. Please generate a quiz first via POST /api/quiz/"
                                    + lectureId + "/generate"));
        }

        String userId = (principal != null) ? principal.getName() : null;

        try {
            QuizSubmitResponse result = quizService.submitQuiz(
                    lectureId, userId, questions, submitRequest);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Quiz submission failed for lectureId={}: {}", lectureId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Quiz submission failed: " + e.getMessage()));
        }
    }
}
