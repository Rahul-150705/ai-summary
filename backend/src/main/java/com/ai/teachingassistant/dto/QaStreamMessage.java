package com.ai.teachingassistant.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * WebSocket message DTO sent to /topic/lectures/{lectureId}/qa.
 *
 * Message flow for streaming RAG Q&amp;A:
 *
 * <pre>
 * ┌──────────────────────┬──────────────────────────────────────────────────┐
 * │ type                 │ Payload fields                                  │
 * ├──────────────────────┼──────────────────────────────────────────────────┤
 * │ ANSWER_CHUNK         │ chunk (text fragment from Ollama)               │
 * │ ANSWER_COMPLETED     │ fullAnswer, sourceChunks, chunksUsed            │
 * │ ANSWER_ERROR         │ error (human-readable error message)            │
 * └──────────────────────┴──────────────────────────────────────────────────┘
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QaStreamMessage {

    /** Message type: ANSWER_CHUNK, ANSWER_COMPLETED, or ANSWER_ERROR */
    private String type;

    /** The lecture this message belongs to. Always present. */
    private String lectureId;

    /** The original question. Present in ANSWER_COMPLETED. */
    private String question;

    /** Text fragment from the LLM (only for ANSWER_CHUNK). */
    private String chunk;

    /** Complete concatenated answer (only for ANSWER_COMPLETED). */
    private String fullAnswer;

    /** Relevant source chunks from the vector store (only for ANSWER_COMPLETED). */
    private List<String> sourceChunks;

    /** Number of chunks retrieved (only for ANSWER_COMPLETED). */
    private Integer chunksUsed;

    /** Error description (only for ANSWER_ERROR). */
    private String error;

    // ── Static factory methods ──────────────────────────────────────────

    public static QaStreamMessage chunk(String lectureId, String chunk) {
        return QaStreamMessage.builder()
                .type("ANSWER_CHUNK")
                .lectureId(lectureId)
                .chunk(chunk)
                .build();
    }

    public static QaStreamMessage completed(String lectureId, String question,
            String fullAnswer, List<String> sourceChunks, int chunksUsed) {
        return QaStreamMessage.builder()
                .type("ANSWER_COMPLETED")
                .lectureId(lectureId)
                .question(question)
                .fullAnswer(fullAnswer)
                .sourceChunks(sourceChunks)
                .chunksUsed(chunksUsed)
                .build();
    }

    public static QaStreamMessage error(String lectureId, String error) {
        return QaStreamMessage.builder()
                .type("ANSWER_ERROR")
                .lectureId(lectureId)
                .error(error)
                .build();
    }
}
