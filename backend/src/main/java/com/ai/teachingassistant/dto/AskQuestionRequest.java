package com.ai.teachingassistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request body for POST /api/lecture/{lectureId}/ask
 */
@Data
public class AskQuestionRequest {

    @NotBlank(message = "Question must not be blank")
    @Size(min = 3, max = 1000, message = "Question must be between 3 and 1000 characters")
    private String question;
}
