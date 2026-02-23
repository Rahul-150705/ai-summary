package com.ai.teachingassistant.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * JwtUtil handles all JWT operations:
 * - Generating access tokens (short-lived, 15 min)
 * - Generating refresh tokens (long-lived, 7 days)
 * - Validating tokens
 * - Extracting claims (email, expiry, token type)
 */
@Slf4j
@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secretKey;

    @Value("${jwt.access-token.expiration:900000}")
    private long accessTokenExpiration; // 15 minutes default

    @Value("${jwt.refresh-token.expiration:604800000}")
    private long refreshTokenExpiration; // 7 days default

    private static final String TOKEN_TYPE_CLAIM = "tokenType";
    private static final String ACCESS_TOKEN = "access";
    private static final String REFRESH_TOKEN = "refresh";

    // ─────────────────────────────────────────────────────────────────────────
    // Token Generation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Generates a short-lived ACCESS token (15 minutes).
     */
    public String generateAccessToken(UserDetails userDetails) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(TOKEN_TYPE_CLAIM, ACCESS_TOKEN);
        return buildToken(claims, userDetails.getUsername(), accessTokenExpiration);
    }

    /**
     * Generates a long-lived REFRESH token (7 days).
     * Used to obtain a new access token without re-login.
     */
    public String generateRefreshToken(UserDetails userDetails) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(TOKEN_TYPE_CLAIM, REFRESH_TOKEN);
        return buildToken(claims, userDetails.getUsername(), refreshTokenExpiration);
    }

    private String buildToken(Map<String, Object> extraClaims, String subject, long expiration) {
        return Jwts.builder()
                .claims(extraClaims)
                .subject(subject)
                .id(UUID.randomUUID().toString()) // unique jti per token
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getSigningKey())
                .compact();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Token Validation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Validates that a token belongs to the user and hasn't expired.
     */
    public boolean isTokenValid(String token, UserDetails userDetails) {
        try {
            final String username = extractUsername(token);
            return username.equals(userDetails.getUsername()) && !isTokenExpired(token);
        } catch (JwtException e) {
            log.warn("JWT validation failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Validates a refresh token (checks type claim + expiry).
     */
    public boolean isRefreshToken(String token) {
        try {
            String tokenType = extractClaim(token, claims -> claims.get(TOKEN_TYPE_CLAIM, String.class));
            return REFRESH_TOKEN.equals(tokenType) && !isTokenExpired(token);
        } catch (JwtException e) {
            log.warn("Refresh token validation failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Validates an access token (checks type claim).
     */
    public boolean isAccessToken(String token) {
        try {
            String tokenType = extractClaim(token, claims -> claims.get(TOKEN_TYPE_CLAIM, String.class));
            return ACCESS_TOKEN.equals(tokenType);
        } catch (JwtException e) {
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Claims Extraction
    // ─────────────────────────────────────────────────────────────────────────

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    public long getAccessTokenExpiration() {
        return accessTokenExpiration;
    }

    public long getRefreshTokenExpiration() {
        return refreshTokenExpiration;
    }

    /** Extracts the unique jti (JWT ID) claim — used for blacklisting. */
    public String extractTokenId(String token) {
        return extractClaim(token, Claims::getId);
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    private SecretKey getSigningKey() {
        byte[] keyBytes = Decoders.BASE64.decode(secretKey);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}