package com.ai.teachingassistant.repository;

import com.ai.teachingassistant.model.Lecture;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LectureRepository extends JpaRepository<Lecture, String> {

    List<Lecture> findByUserIdOrderByProcessedAtDesc(String userId);

    Optional<Lecture> findFirstByContentHash(String contentHash);

    long countByUserId(String userId);

    @Query("SELECT COALESCE(SUM(l.pageCount), 0) FROM Lecture l WHERE l.userId = :userId")
    long sumPageCountByUserId(@Param("userId") String userId);
}
