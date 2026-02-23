package com.ai.teachingassistant.repository;

import com.ai.teachingassistant.model.Lecture;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for Lecture entities.
 * Backed by the `lectures` table in PostgreSQL.
 */
@Repository
public interface LectureRepository extends JpaRepository<Lecture, String> {

    /**
     * Retrieve all lectures belonging to a specific user, most recent first.
     */
    List<Lecture> findByUserIdOrderByProcessedAtDesc(String userId);

    /**
     * Look up an existing lecture by its MD5 content hash.
     * Used for cache lookups: if the same PDF bytes were processed before,
     * return the cached summary without calling the LLM again.
     */
    Optional<Lecture> findFirstByContentHash(String contentHash);
}
