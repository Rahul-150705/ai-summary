package com.ai.teachingassistant.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Lecture model representing a processed lecture document.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Lecture {

    private String id;
    private String fileName;
    private String originalText;
    private String provider;
    private LocalDateTime processedAt;
    private long fileSizeBytes;
    private int pageCount;
}