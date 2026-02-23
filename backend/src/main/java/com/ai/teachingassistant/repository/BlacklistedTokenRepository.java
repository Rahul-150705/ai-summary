package com.ai.teachingassistant.repository;

import com.ai.teachingassistant.model.BlacklistedToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Repository for JWT token blacklist entries.
 */
@Repository
public interface BlacklistedTokenRepository extends JpaRepository<BlacklistedToken, String> {

    /** Check whether a token (by jti) has been revoked. */
    boolean existsByTokenId(String tokenId);

    /** Purge expired tokens to keep the table lean (called by scheduled task). */
    @Modifying
    @Transactional
    void deleteByExpiresAtBefore(LocalDateTime cutoff);
}
