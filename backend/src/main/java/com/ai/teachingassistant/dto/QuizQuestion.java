package com.ai.teachingassistant.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Represents a single MCQ quiz question generated from a lecture.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuizQuestion {

    /** 0-based index of this question in the quiz. */
    private int index;

    /** The question text. */
    private String question;

    /** Four answer options labelled A, B, C, D. */
    private List<String> options;

    /**
     * The correct option letter: "A", "B", "C", or "D".
     * Returned by the backend so the frontend can mark answers.
     */
    private String correctAnswer;

    /** Brief explanation of why the correct answer is right. */
    private String explanation;
}
