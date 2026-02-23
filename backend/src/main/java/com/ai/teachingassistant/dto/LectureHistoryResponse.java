package com.ai.teachingassistant.dto;

import com.ai.teachingassistant.model.Lecture;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Lightweight DTO for the lecture history list.
 * Excludes originalText to keep the response payload small.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LectureHistoryResponse {

    private String id;
    private String fileName;
    private String provider;
    private LocalDateTime processedAt;
    private long fileSizeBytes;
    private int pageCount;

    /** Maps a Lecture entity to this lightweight DTO. */
    public static LectureHistoryResponse from(Lecture lecture) {
        return LectureHistoryResponse.builder()
                .id(lecture.getId())
                .fileName(lecture.getFileName())
                .provider(lecture.getProvider())
                .processedAt(lecture.getProcessedAt())
                .fileSizeBytes(lecture.getFileSizeBytes())
                .pageCount(lecture.getPageCount())
                .build();
    }
}
