package com.ai.teachingassistant.auth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response body returned after successful login or signup.
 * Contains JWT access token, refresh token, and user info.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {

    private String accessToken;
    private String refreshToken;

    @Builder.Default
    private String tokenType = "Bearer";

    private long   accessExpiresIn;   // milliseconds until access token expires
    private long   refreshExpiresIn;  // milliseconds until refresh token expires

    private String email;
    private String fullName;
}