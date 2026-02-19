package com.ai.teachingassistant.auth;

import com.ai.teachingassistant.model.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory UserRepository using a thread-safe ConcurrentHashMap.
 * Keyed by email (the unique identifier / username).
 *
 * NOTE: Data is lost on server restart.
 * Replace with a JPA repository + database for production.
 */
@Slf4j
@Repository
public class UserRepository {

    // email → User
    private final Map<String, User> store = new ConcurrentHashMap<>();

    public Optional<User> findByEmail(String email) {
        return Optional.ofNullable(store.get(email.toLowerCase()));
    }

    public boolean existsByEmail(String email) {
        return store.containsKey(email.toLowerCase());
    }

    public User save(User user) {
        store.put(user.getEmail().toLowerCase(), user);
        log.info("User saved: {}", user.getEmail());
        return user;
    }

    public int count() {
        return store.size();
    }
}