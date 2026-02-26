package com.ai.teachingassistant.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Unified response for POST /api/lecture/process.
 *
 * <ul>
 * <li><b>mode=summary</b>: {@code status="complete"}, {@code summary} is fully
 * populated.</li>
 * <li><b>mode=chat | mode=quiz</b>: {@code status="indexing_complete"},
 * {@code summary} is null
 * (LLM summarization is running in the background). The lectureId is
 * immediately
 * usable for RAG Q&amp;A and quiz generation.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProcessResponse {

    /**
     * The saved lecture's UUID — use with /api/lecture/{id}/ask and
     * /api/quiz/{id}/generate
     */
    private String lectureId;

    /** The mode the user selected: "summary" | "chat" | "quiz" */
    private String mode;

    /**
     * Processing status:
     * <ul>
     * <li>{@code "complete"} — full summary available (mode=summary)</li>
     * <li>{@code "indexing_complete"} — indexed + background summary in progress
     * (mode=chat|quiz)</li>
     * </ul>
     */
    private String status;

    /** Original PDF filename */
    private String fileName;

    /** Number of pages extracted from the PDF */
    private int pageCount;

    /**
     * Number of text chunks stored in the vector store (0 = already indexed / cache
     * hit)
     */
    private int chunksIndexed;

    /**
     * Fully populated AI summary — only present when mode=summary.
     * Null for chat/quiz mode (summary is generated asynchronously).
     */
    private SummaryResponse summary;
}
