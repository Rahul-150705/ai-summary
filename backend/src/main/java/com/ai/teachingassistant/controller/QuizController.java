package com.ai.teachingassistant.controller;

import com.ai.teachingassistant.dto.QuizQuestion;
import com.ai.teachingassistant.dto.QuizResponse;
import com.ai.teachingassistant.dto.QuizSubmitRequest;
import com.ai.teachingassistant.dto.QuizSubmitResponse;
import com.ai.teachingassistant.model.QuizAttempt;
import com.ai.teachingassistant.repository.LectureRepository;
import com.ai.teachingassistant.repository.QuizAttemptRepository;
import com.ai.teachingassistant.service.QuizService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.ai.teachingassistant.model.Lecture;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/quiz")
@RequiredArgsConstructor
public class QuizController {

    private final QuizService quizService;
    private final QuizAttemptRepository quizAttemptRepository;
    private final LectureRepository lectureRepository;

    // Removed ConcurrentHashMap since we now store in DB

    // ── POST /api/quiz/{lectureId}/generate ──────────────────────────────────

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
            
            Lecture lecture = lectureRepository.findById(lectureId).orElse(null);
            if (lecture != null) {
                ObjectMapper mapper = new ObjectMapper();
                lecture.setQuizData(mapper.writeValueAsString(quiz.getQuestions()));
                lectureRepository.save(lecture);
            }
            
            return ResponseEntity.ok(quiz);
        } catch (Exception e) {
            log.error("Quiz generation failed for lectureId={}: {}", lectureId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Quiz generation failed: " + e.getMessage()));
        }
    }

    // ── POST /api/quiz/{lectureId}/submit ────────────────────────────────────

    @PostMapping("/{lectureId}/submit")
    public ResponseEntity<?> submitQuiz(
            @PathVariable String lectureId,
            @RequestBody QuizSubmitRequest submitRequest,
            Principal principal) {

        List<QuizQuestion> questions = null;
        try {
            Lecture lecture = lectureRepository.findById(lectureId).orElse(null);
            if (lecture != null && lecture.getQuizData() != null) {
                ObjectMapper mapper = new ObjectMapper();
                questions = mapper.readValue(lecture.getQuizData(), new TypeReference<List<QuizQuestion>>() {});
            }
        } catch (Exception e) {
            log.warn("Failed to retrieve quiz questions from DB for lectureId={}", lectureId, e);
        }

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

            // ── Persist the attempt so stats & history work ──────────────
            try {
                String fileName = lectureRepository.findById(lectureId)
                        .map(l -> l.getFileName()).orElse("Unknown");

                quizAttemptRepository.save(QuizAttempt.builder()
                        .lectureId(lectureId)
                        .lectureFileName(fileName)
                        .userId(userId)
                        .score(result.getScore())
                        .totalQuestions(result.getTotalQuestions())
                        .percentage(result.getPercentage())
                        .grade(result.getGrade())
                        .build());

                log.info("Quiz attempt saved: user={}, lecture={}, score={}%",
                        userId, lectureId, result.getPercentage());
            } catch (Exception saveEx) {
                log.warn("Failed to save quiz attempt (non-fatal): {}", saveEx.getMessage());
            }

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Quiz submission failed for lectureId={}: {}", lectureId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Quiz submission failed: " + e.getMessage()));
        }
    }

    // ── GET /api/quiz/history ────────────────────────────────────────────────

    @GetMapping("/history")
    public ResponseEntity<?> getQuizHistory(Principal principal) {
        if (principal == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not authenticated"));

        List<QuizAttempt> attempts = quizAttemptRepository
                .findByUserIdOrderByAttemptedAtDesc(principal.getName());
        return ResponseEntity.ok(attempts);
    }
}
