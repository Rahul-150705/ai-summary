package com.ai.teachingassistant.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Sent by the frontend when the user submits their quiz answers.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuizSubmitRequest {

    /**
     * Each entry is the user's selected option ("A"/"B"/"C"/"D") for that question
     * index.
     */
    private List<String> answers;
}
