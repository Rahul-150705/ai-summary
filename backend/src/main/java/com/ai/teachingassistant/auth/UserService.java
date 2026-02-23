package com.ai.teachingassistant.auth;

import com.ai.teachingassistant.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * UserService implements Spring Security's UserDetailsService so the
 * framework can load users during authentication.
 * Also handles registration logic (hashing passwords, saving users).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService implements UserDetailsService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * Called by Spring Security during authentication to load user by email.
     */
    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    log.warn("User not found: {}", email);
                    return new UsernameNotFoundException("User not found: " + email);
                });
    }

    /**
     * Registers a new user. Validates email uniqueness and hashes password.
     *
     * @param fullName    Display name
     * @param email       Unique email (used as login username)
     * @param rawPassword Plain text password (will be BCrypt hashed)
     * @return The saved User entity
     * @throws IllegalArgumentException if email is already registered
     */
    public User registerUser(String fullName, String email, String rawPassword) {
        if (userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("An account with this email already exists.");
        }

        User user = User.builder()
                .fullName(fullName)
                .email(email.toLowerCase())
                .password(passwordEncoder.encode(rawPassword))
                .role("ROLE_USER")
                .createdAt(LocalDateTime.now())
                .build();

        return userRepository.save(user);
    }
}