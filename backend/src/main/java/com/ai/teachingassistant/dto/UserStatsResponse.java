package com.ai.teachingassistant.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response for GET /api/user/stats
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserStatsResponse {

    private long totalLectures;
    private long totalPagesProcessed;
    private long totalQuizAttempts;
    private int averageQuizScore; // 0-100 percentage
    private long studyDaysThisMonth;

    /** Last 10 quiz attempts (most recent first) for the score history panel */
    private List<QuizAttemptSummary> recentQuizzes;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuizAttemptSummary {
        private String lectureFileName;
        private int percentage;
        private String grade;
        private String attemptedAt;
    }
}
