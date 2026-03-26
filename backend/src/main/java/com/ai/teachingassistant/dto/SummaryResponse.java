package com.ai.teachingassistant.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Data Transfer Object for AI-generated PDF summaries returned to the frontend.
 *
 * <p>Sections map 1:1 to the LLM prompt markers:</p>
 * <pre>
 * [MAIN_TOPIC]         → mainTopic
 * [KEY_POINTS]          → keyPoints
 * [IMPORTANT_DETAILS]   → importantDetails
 * [STRUCTURE_OVERVIEW]  → structureOverview
 * [CONCLUSIONS]         → conclusions
 * [NOTABLE_QUOTES]      → notableQuotes
 * [ADDITIONAL_NOTES]    → additionalNotes
 * </pre>
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

    /** Descriptive title of the document. */
    private String title;

    /** Main Topic & Purpose — what the document is about and its goal. */
    private String mainTopic;

    /** Key Points & Arguments — major points, findings, or arguments. */
    @JsonProperty("keyPoints")
    private List<String> keyPoints;

    /** Important Details — specific data, statistics, dates, names, or figures. */
    @JsonProperty("importantDetails")
    private List<String> importantDetails;

    /** Structure Overview — how the document is organized (sections, chapters, etc.). */
    private String structureOverview;

    /** Conclusions & Recommendations — final conclusions or action items. */
    @JsonProperty("conclusions")
    private List<String> conclusions;

    /** Notable Quotes — critical or standout statements from the document. */
    @JsonProperty("notableQuotes")
    private List<String> notableQuotes;

    /** Additional Notes — anything unusual, important, or easily overlooked. */
    @JsonProperty("additionalNotes")
    private List<String> additionalNotes;

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