package com.ai.teachingassistant.auth;

import com.ai.teachingassistant.model.User;
import com.ai.teachingassistant.security.JwtUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * AuthController exposes public authentication endpoints:
 *
 *  POST /api/auth/signup   → Register new account
 *  POST /api/auth/login    → Login and receive JWT tokens
 *  POST /api/auth/refresh  → Exchange refresh token for new access token
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:3000"})
public class AuthController {

    private final UserService           userService;
    private final JwtUtil               jwtUtil;
    private final AuthenticationManager authenticationManager;

    // ── POST /api/auth/signup ─────────────────────────────────────────────

    @PostMapping("/signup")
    public ResponseEntity<?> signup(@Valid @RequestBody SignupRequest request) {
        log.info("Signup attempt for email: {}", request.getEmail());

        try {
            User user = userService.registerUser(
                    request.getFullName(),
                    request.getEmail(),
                    request.getPassword()
            );

            // Auto-login: generate tokens immediately after signup
            String accessToken  = jwtUtil.generateAccessToken(user);
            String refreshToken = jwtUtil.generateRefreshToken(user);

            log.info("Signup successful for: {}", user.getEmail());

            return ResponseEntity.status(HttpStatus.CREATED).body(
                    AuthResponse.builder()
                            .accessToken(accessToken)
                            .refreshToken(refreshToken)
                            .tokenType("Bearer")
                            .accessExpiresIn(jwtUtil.getAccessTokenExpiration())
                            .refreshExpiresIn(jwtUtil.getRefreshTokenExpiration())
                            .email(user.getEmail())
                            .fullName(user.getFullName())
                            .build()
            );

        } catch (IllegalArgumentException e) {
            log.warn("Signup failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ── POST /api/auth/login ──────────────────────────────────────────────

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody AuthRequest request) {
        log.info("Login attempt for email: {}", request.getEmail());

        try {
            // Authenticate via Spring Security (validates password against BCrypt hash)
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            request.getEmail(),
                            request.getPassword()
                    )
            );

            UserDetails userDetails = userService.loadUserByUsername(request.getEmail());
            User user = (User) userDetails;

            String accessToken  = jwtUtil.generateAccessToken(userDetails);
            String refreshToken = jwtUtil.generateRefreshToken(userDetails);

            log.info("Login successful for: {}", request.getEmail());

            return ResponseEntity.ok(
                    AuthResponse.builder()
                            .accessToken(accessToken)
                            .refreshToken(refreshToken)
                            .tokenType("Bearer")
                            .accessExpiresIn(jwtUtil.getAccessTokenExpiration())
                            .refreshExpiresIn(jwtUtil.getRefreshTokenExpiration())
                            .email(user.getEmail())
                            .fullName(user.getFullName())
                            .build()
            );

        } catch (BadCredentialsException e) {
            log.warn("Login failed for: {} — bad credentials", request.getEmail());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid email or password."));
        }
    }

    // ── POST /api/auth/refresh ────────────────────────────────────────────

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        log.info("Token refresh attempt");

        String refreshToken = request.getRefreshToken();

        try {
            // Validate it's actually a refresh token (not an access token being misused)
            if (!jwtUtil.isRefreshToken(refreshToken)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Invalid or expired refresh token."));
            }

            String email        = jwtUtil.extractUsername(refreshToken);
            UserDetails user    = userService.loadUserByUsername(email);
            String newAccess    = jwtUtil.generateAccessToken(user);
            String newRefresh   = jwtUtil.generateRefreshToken(user); // rotate refresh token

            log.info("Token refreshed for: {}", email);

            return ResponseEntity.ok(
                    AuthResponse.builder()
                            .accessToken(newAccess)
                            .refreshToken(newRefresh)
                            .tokenType("Bearer")
                            .accessExpiresIn(jwtUtil.getAccessTokenExpiration())
                            .refreshExpiresIn(jwtUtil.getRefreshTokenExpiration())
                            .email(email)
                            .fullName(((User) user).getFullName())
                            .build()
            );

        } catch (Exception e) {
            log.error("Token refresh failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Token refresh failed. Please log in again."));
        }
    }
}