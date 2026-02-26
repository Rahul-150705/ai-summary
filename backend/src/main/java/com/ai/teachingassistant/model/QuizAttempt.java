package com.ai.teachingassistant.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Persists a single quiz attempt so we can:
 * - Show quiz score history per lecture
 * - Compute aggregate stats (average score, total attempts)
 * - Calculate study streak on the dashboard
 */
@Entity
@Table(name = "quiz_attempts")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuizAttempt {

    @Id
    @Column(length = 36)
    private String id;

    /** FK to the lecture the quiz was generated for */
    @Column(name = "lecture_id", nullable = false, length = 36)
    private String lectureId;

    /** Original PDF filename — stored so we can display it in history */
    @Column(name = "lecture_filename")
    private String lectureFileName;

    /** Authenticated user who took the quiz */
    @Column(name = "user_id")
    private String userId;

    /** Number of correct answers */
    @Column(nullable = false)
    private int score;

    /** Total number of questions */
    @Column(name = "total_questions", nullable = false)
    private int totalQuestions;

    /** Score % (0-100) — stored for easy querying/averaging */
    @Column(nullable = false)
    private int percentage;

    /** Friendly grade string: "Excellent 🏆", "Good 👍", etc. */
    @Column(length = 50)
    private String grade;

    @CreationTimestamp
    @Column(name = "attempted_at", nullable = false, updatable = false)
    private LocalDateTime attemptedAt;

    @PrePersist
    public void prePersist() {
        if (id == null)
            id = UUID.randomUUID().toString();
    }
}
