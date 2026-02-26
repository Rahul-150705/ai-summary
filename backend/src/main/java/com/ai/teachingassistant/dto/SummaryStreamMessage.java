package com.ai.teachingassistant.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Unified WebSocket message DTO sent to /topic/lectures/{lectureId}.
 *
 * Every message has a {@code type} discriminator so the React client can
 * handle each event with a simple switch/case:
 *
 * <pre>
 * ┌──────────────────────┬──────────────────────────────────────────────┐
 * │ type                 │ Payload fields                              │
 * ├──────────────────────┼──────────────────────────────────────────────┤
 * │ SUMMARY_CHUNK        │ chunk (text fragment from Ollama)           │
 * │ SUMMARY_COMPLETED    │ lectureId, fullSummary (final text)         │
 * │ SUMMARY_ERROR        │ error (human-readable error message)        │
 * └──────────────────────┴──────────────────────────────────────────────┘
 * </pre>
 *
 * Example JSON payloads:
 *
 * <b>SUMMARY_CHUNK</b>
 * 
 * <pre>
 * {
 *   "type": "SUMMARY_CHUNK",
 *   "lectureId": "abc-123",
 *   "chunk": "The main theme of this lecture..."
 * }
 * </pre>
 *
 * <b>SUMMARY_COMPLETED</b>
 * 
 * <pre>
 * {
 *   "type": "SUMMARY_COMPLETED",
 *   "lectureId": "abc-123",
 *   "fullSummary": "...the entire concatenated summary..."
 * }
 * </pre>
 *
 * <b>SUMMARY_ERROR</b>
 * 
 * <pre>
 * {
 *   "type": "SUMMARY_ERROR",
 *   "lectureId": "abc-123",
 *   "error": "Ollama service is unreachable."
 * }
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL) // omit null fields from JSON
public class SummaryStreamMessage {

    /**
     * Message type discriminator.
     * One of: SUMMARY_CHUNK, SUMMARY_COMPLETED, SUMMARY_ERROR
     */
    private String type;

    /** The lecture this message belongs to. Always present. */
    private String lectureId;

    /** Text fragment from the LLM (only for SUMMARY_CHUNK). */
    private String chunk;

    /** Complete concatenated summary (only for SUMMARY_COMPLETED). */
    private String fullSummary;

    /** Error description (only for SUMMARY_ERROR). */
    private String error;

    // ── Static factory methods for clean construction ────────────────────

    public static SummaryStreamMessage chunk(String lectureId, String chunk) {
        return SummaryStreamMessage.builder()
                .type("SUMMARY_CHUNK")
                .lectureId(lectureId)
                .chunk(chunk)
                .build();
    }

    public static SummaryStreamMessage completed(String lectureId, String fullSummary) {
        return SummaryStreamMessage.builder()
                .type("SUMMARY_COMPLETED")
                .lectureId(lectureId)
                .fullSummary(fullSummary)
                .build();
    }

    public static SummaryStreamMessage error(String lectureId, String error) {
        return SummaryStreamMessage.builder()
                .type("SUMMARY_ERROR")
                .lectureId(lectureId)
                .error(error)
                .build();
    }
}
