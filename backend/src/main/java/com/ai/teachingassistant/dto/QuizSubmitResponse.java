package com.ai.teachingassistant.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Returned after the user submits their quiz answers.
 * Contains per-question results and an overall score.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuizSubmitResponse {

    /** Overall score (number of correct answers). */
    private int score;

    /** Total number of questions in the quiz. */
    private int totalQuestions;

    /** Score as a percentage (0-100). */
    private int percentage;

    /** A friendly grade label: "Excellent", "Good", "Needs Improvement", etc. */
    private String grade;

    /** Per-question breakdown. */
    private List<QuestionResult> results;

    /**
     * Result for a single question.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuestionResult {

        /** 0-based index matching the original quiz question. */
        private int index;

        /** The question text. */
        private String question;

        /** The option the user selected. */
        private String selectedAnswer;

        /** The correct option ("A"/"B"/"C"/"D"). */
        private String correctAnswer;

        /** Whether the user got it right. */
        private boolean correct;

        /** Explanation of the correct answer. */
        private String explanation;
    }
}
