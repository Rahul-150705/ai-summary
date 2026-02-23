package com.ai.teachingassistant.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Lecture entity representing a processed lecture document.
 * Persisted to the `lectures` table via Spring Data JPA.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "lectures")
public class Lecture {

    @Id
    @Column(nullable = false, updatable = false)
    private String id; // UUID assigned before save

    @Column(nullable = false)
    private String fileName;

    @Column(columnDefinition = "TEXT")
    private String originalText;

    @Column(columnDefinition = "TEXT")
    private String summary; // AI-generated summary stored for history

    @Column
    private String provider; // LLM provider used (openai / ollama / claude / gemini)

    @Column(nullable = false)
    private LocalDateTime processedAt;

    @Column
    private long fileSizeBytes;

    @Column
    private int pageCount;

    @Column
    private String userId; // ID of the user who uploaded the lecture

    /**
     * MD5 hash of the raw PDF bytes.
     * Used as a content-based cache key: if the same file is uploaded again
     * the existing summary is returned without calling the LLM.
     */
    @Column(length = 32)
    private String contentHash;
}