package com.ai.teachingassistant.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Persisted record of a revoked JWT token.
 * Keyed by the token's unique "jti" (JWT ID) claim.
 * The JwtAuthFilter checks this table on every request.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "token_blacklist", indexes = {
        @Index(name = "idx_token_blacklist_expires", columnList = "expiresAt")
})
public class BlacklistedToken {

    /** JWT ID claim — unique per token, used as primary key. */
    @Id
    @Column(nullable = false, updatable = false)
    private String tokenId;

    /** Email of the user who owned this token. */
    @Column(nullable = false)
    private String email;

    /** When the token naturally expires — used for scheduled cleanup. */
    @Column(nullable = false)
    private LocalDateTime expiresAt;

    /** When this token was blacklisted (logout time). */
    @Column(nullable = false)
    private LocalDateTime blacklistedAt;
}
