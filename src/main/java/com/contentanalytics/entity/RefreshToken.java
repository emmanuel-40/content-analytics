package com.contentanalytics.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Server-side record of an issued refresh token.
 *
 * The raw JWT is never stored: only its SHA-256 digest
 * ({@link #tokenHash}) is kept, so a database leak cannot be replayed against
 * the authentication endpoint. Tokens minted by one login (or rotation) share
 * a {@link #familyId}; seeing any member of a family presented twice is proof
 * of theft and revokes every token in that family.
 *
 * Atomicity is enforced in the database by
 * {@link com.contentanalytics.repository.RefreshTokenRepository#claimToken},
 * not by optimistic locking on this entity - two concurrent refreshes must not
 * both succeed, and a conditional UPDATE gives us that guarantee.
 */
@Entity
@Table(name = "refresh_tokens", indexes = {
        @Index(name = "idx_refresh_tokens_user_id", columnList = "user_id"),
        @Index(name = "idx_refresh_tokens_family_id", columnList = "family_id"),
        @Index(name = "idx_refresh_tokens_token_hash", columnList = "token_hash", unique = true),
        @Index(name = "idx_refresh_tokens_expires_at", columnList = "expires_at"),
        @Index(name = "idx_refresh_tokens_status", columnList = "status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Hex-encoded SHA-256 of the raw refresh JWT. Unique, so hashes are also the lookup key. */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    /** Groups all successive tokens of one login session. */
    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private RefreshTokenStatus status = RefreshTokenStatus.ACTIVE;

    /** Explicit used flag, kept in lockstep with {@link #status} for easy auditing. */
    @Column(nullable = false)
    @Builder.Default
    private Boolean used = false;

    /** Explicit revoked flag, kept in lockstep with {@link #status} for easy auditing. */
    @Column(nullable = false)
    @Builder.Default
    private Boolean revoked = false;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Id of the refresh token that replaced this one after a successful rotation. */
    @Column(name = "replaced_by")
    private UUID replacedBy;

    public void markUsed(LocalDateTime at, UUID replacementId) {
        this.status = RefreshTokenStatus.USED;
        this.used = true;
        this.usedAt = at;
        this.replacedBy = replacementId;
    }

    public void markRevoked(LocalDateTime at) {
        this.status = RefreshTokenStatus.REVOKED;
        this.revoked = true;
        this.revokedAt = at;
    }
}
