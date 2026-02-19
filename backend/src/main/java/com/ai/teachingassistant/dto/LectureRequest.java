package com.ai.teachingassistant.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Data Transfer Object for incoming lecture summarization requests.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LectureRequest {

    private String fileName;

    // Optional: override default LLM provider per-request
    private String llmProvider;

    // Optional: summary detail level (basic | detailed)
    private String detailLevel;
}