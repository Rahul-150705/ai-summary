package com.ai.teachingassistant.repository;

import com.ai.teachingassistant.model.QuizAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface QuizAttemptRepository extends JpaRepository<QuizAttempt, String> {

    List<QuizAttempt> findByUserIdOrderByAttemptedAtDesc(String userId);

    long countByUserId(String userId);

    @Query("SELECT COALESCE(AVG(q.percentage), 0) FROM QuizAttempt q WHERE q.userId = :userId")
    double averagePercentageByUserId(@Param("userId") String userId);

    /** Count distinct days (as date strings) the user has any quiz activity */
    @Query("SELECT COUNT(DISTINCT CAST(q.attemptedAt AS date)) FROM QuizAttempt q WHERE q.userId = :userId AND q.attemptedAt >= :since")
    long countActiveDaysSince(@Param("userId") String userId, @Param("since") LocalDateTime since);
}
