package com.ai.teachingassistant.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Data Transfer Object for AI-generated lecture summaries returned to the
 * frontend.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SummaryResponse {

    /**
     * The database ID of this saved lecture — used by the frontend to call
     * /api/quiz/{lectureId}/generate
     */
    private String lectureId;

    /** High-level title of the summarised lecture. */
    private String title;

    /** 3-5 sentence overview paragraph about the lecture. */
    private String overview;

    /** Bullet-point key concepts (full sentences). */
    @JsonProperty("keyPoints")
    private List<String> keyPoints;

    /** Important definitions extracted from the lecture. */
    private List<String> definitions;

    /** Rich multi-paragraph detailed explanation. */
    private String detailedExplanation;

    /** Exam-focused takeaways (full actionable statements). */
    @JsonProperty("examPoints")
    private List<String> examPoints;

    /** Suggested further reading / resources. */
    private List<String> furtherReading;

    /**
     * Pre-built Markdown string combining all sections above.
     * Render this directly in the frontend with any Markdown library (e.g.
     * react-markdown, marked.js).
     */
    private String markdownSummary;

    /** Name of the source PDF file. */
    private String fileName;

    /**
     * LLM provider that generated this summary (openai / claude / gemini / ollama).
     */
    private String provider;

    /** Timestamp of when the summary was generated. */
    private LocalDateTime generatedAt;

    /** Total pages extracted from the PDF. */
    private int pageCount;

    /**
     * True when this summary was served from the cache (same PDF was previously
     * processed) — no LLM call was made. False for freshly generated summaries.
     */
    private boolean fromCache;
}