package com.ai.teachingassistant.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Response body for POST /api/lecture/{lectureId}/ask
 */
@Data
@Builder
public class AskQuestionResponse {

    /** The lectureId that was queried. */
    private String lectureId;

    /** The original question asked by the user. */
    private String question;

    /** The LLM-generated answer, grounded in the lecture's content. */
    private String answer;

    /**
     * The relevant text chunks that were retrieved from the vector store
     * and passed to the LLM as context. Useful for debugging / showing
     * "sources" to the user.
     */
    private List<String> sourceChunks;

    /** How many chunks were retrieved from the vector store. */
    private int chunksUsed;
}
