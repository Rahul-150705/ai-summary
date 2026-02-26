package com.ai.teachingassistant.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response returned after a "quick index" upload —
 * PDF is extracted and indexed into the vector store but NO summary is
 * generated.
 * The lectureId can immediately be used for RAG Q&A and quiz generation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuickIndexResponse {

    /**
     * The database ID of this lecture — use with /api/quiz/{id} and
     * /api/lecture/{id}/ask
     */
    private String lectureId;

    /** Original PDF filename */
    private String fileName;

    /** Number of pages extracted */
    private int pageCount;

    /** Approximate number of text chunks indexed into the vector store */
    private int chunksIndexed;

    /**
     * Always "quick_index" — lets the frontend distinguish from a full summary
     * response
     */
    private String mode;
}
