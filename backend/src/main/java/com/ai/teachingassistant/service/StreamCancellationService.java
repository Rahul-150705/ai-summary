package com.ai.teachingassistant.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages cancellation flags for active AI streaming sessions.
 *
 * <p>Both summary streaming and Q&A streaming check this service
 * to know when the user has requested a stop. The cancel flag is
 * keyed by {@code lectureId} — one active stream per lecture.</p>
 *
 * <h3>Usage</h3>
 * <pre>
 *   // Before starting a stream
 *   cancellationService.clearCancellation(lectureId);
 *
 *   // Inside the stream loop
 *   if (cancellationService.isCancelled(lectureId)) break;
 *
 *   // When the user clicks "Stop"
 *   cancellationService.cancel(lectureId);
 * </pre>
 */
@Slf4j
@Service
public class StreamCancellationService {

    /** Set of lectureIds that have been requested to stop. */
    private final Set<String> cancelledStreams = ConcurrentHashMap.newKeySet();

    /**
     * Marks the given lectureId as cancelled. Active streams checking this
     * flag will stop at the next iteration.
     */
    public void cancel(String lectureId) {
        cancelledStreams.add(lectureId);
        log.info("Stream cancellation requested for lectureId={}", lectureId);
    }

    /**
     * Returns true if the stream for this lectureId should stop.
     */
    public boolean isCancelled(String lectureId) {
        return cancelledStreams.contains(lectureId);
    }

    /**
     * Clears the cancellation flag. Call this before starting a new stream
     * so that a previous cancellation doesn't immediately stop a new one.
     */
    public void clearCancellation(String lectureId) {
        cancelledStreams.remove(lectureId);
    }
}
