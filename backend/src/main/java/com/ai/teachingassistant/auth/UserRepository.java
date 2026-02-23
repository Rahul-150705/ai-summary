package com.ai.teachingassistant.auth;

import com.ai.teachingassistant.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA repository for User entities.
 * Replaces the previous in-memory ConcurrentHashMap implementation.
 * Backed by the `users` table in PostgreSQL.
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}