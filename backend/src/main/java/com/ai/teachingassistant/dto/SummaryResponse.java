package com.ai.teachingassistant.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Data Transfer Object for AI-generated lecture summaries returned to the frontend.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SummaryResponse {

    /** High-level title of the summarized lecture. */
    private String title;

    /** Bullet-point key concepts from the lecture. */
    @JsonProperty("keyPoints")
    private List<String> keyPoints;

    /** Important definitions extracted from the lecture. */
    private List<String> definitions;

    /** Exam-focused takeaways. */
    @JsonProperty("examPoints")
    private List<String> examPoints;

    /** Name of the source PDF file. */
    private String fileName;

    /** LLM provider that generated this summary (openai / claude / gemini). */
    private String provider;

    /** Timestamp of when the summary was generated. */
    private LocalDateTime generatedAt;

    /** Total pages extracted from the PDF. */
    private int pageCount;
}