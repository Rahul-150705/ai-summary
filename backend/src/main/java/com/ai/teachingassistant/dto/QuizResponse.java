package com.ai.teachingassistant.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response returned when a quiz is generated for a lecture.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuizResponse {

    /** ID of the lecture this quiz was generated from. */
    private String lectureId;

    /** Title of the lecture. */
    private String lectureTitle;

    /** The generated quiz questions (MCQ). */
    private List<QuizQuestion> questions;

    /** Total number of questions. */
    private int totalQuestions;
}
