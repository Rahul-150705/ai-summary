package com.ai.teachingassistant.security;

import com.ai.teachingassistant.model.BlacklistedToken;
import com.ai.teachingassistant.repository.BlacklistedTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * TokenBlacklistService manages revoked JWT tokens.
 *
 * On logout, both the access and refresh tokens are blacklisted by their jti.
 * The JwtAuthFilter checks this service before honoring any token.
 * Expired blacklist entries are purged automatically every hour.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenBlacklistService {

    private final BlacklistedTokenRepository blacklistedTokenRepository;
    private final JwtUtil jwtUtil;

    /**
     * Revokes a token by storing its jti in the blacklist table.
     *
     * @param token Raw JWT string to blacklist.
     */
    public void blacklistToken(String token) {
        try {
            String tokenId = jwtUtil.extractTokenId(token);
            String email = jwtUtil.extractUsername(token);
            LocalDateTime expiresAt = jwtUtil.extractExpiration(token)
                    .toInstant()
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime();

            BlacklistedToken entry = BlacklistedToken.builder()
                    .tokenId(tokenId)
                    .email(email)
                    .expiresAt(expiresAt)
                    .blacklistedAt(LocalDateTime.now())
                    .build();

            blacklistedTokenRepository.save(entry);
            log.info("Token blacklisted for user '{}' (jti={})", email, tokenId);

        } catch (Exception e) {
            log.warn("Failed to blacklist token: {}", e.getMessage());
        }
    }

    /**
     * Returns true if the token has been revoked (exists in the blacklist).
     *
     * @param token Raw JWT string to check.
     */
    public boolean isBlacklisted(String token) {
        try {
            String tokenId = jwtUtil.extractTokenId(token);
            return blacklistedTokenRepository.existsByTokenId(tokenId);
        } catch (Exception e) {
            // If we can't parse the token, treat it as not blacklisted
            // (it will fail JwtUtil.isTokenValid anyway)
            return false;
        }
    }

    /**
     * Scheduled cleanup: removes expired blacklist entries every hour.
     * Keeps the table lean — no need to check expired tokens that are
     * naturally invalid anyway.
     */
    @Scheduled(fixedRate = 3_600_000) // every 1 hour
    public void purgeExpiredTokens() {
        blacklistedTokenRepository.deleteByExpiresAtBefore(LocalDateTime.now());
        log.debug("Purged expired blacklisted tokens");
    }
}
