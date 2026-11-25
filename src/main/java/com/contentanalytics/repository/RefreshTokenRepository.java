package com.contentanalytics.repository;

import com.contentanalytics.entity.RefreshToken;
import com.contentanalytics.entity.RefreshTokenStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    List<RefreshToken> findByFamilyId(UUID familyId);

    /**
     * Single-use claim. The WHERE clause is part of the UPDATE statement, so two
     * concurrent refreshes cannot both claim the same row: after the winner
     * commits, the loser's statement re-evaluates the predicate, sees status=USED
     * and affects zero rows. No pessimistic lock or @Version needed.
     *
     * @return 1 when this caller won the token, 0 when it was already used,
     * revoked or expired (or a concurrent request won it).
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE RefreshToken t
               SET t.status = :usedStatus,
                   t.used = true,
                   t.usedAt = :now,
                   t.replacedBy = :replacementId
             WHERE t.tokenHash = :tokenHash
               AND t.status = :activeStatus
               AND t.used = false
               AND t.revoked = false
               AND t.expiresAt > :now
            """)
    int claimToken(@Param("tokenHash") String tokenHash,
                   @Param("now") LocalDateTime now,
                   @Param("replacementId") UUID replacementId,
                   @Param("activeStatus") RefreshTokenStatus activeStatus,
                   @Param("usedStatus") RefreshTokenStatus usedStatus);

    /** Revoke every token in a family - the response to a detected replay. */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE RefreshToken t
               SET t.status = :revokedStatus, t.revoked = true, t.revokedAt = :now
             WHERE t.familyId = :familyId AND t.revoked = false
            """)
    int revokeFamily(@Param("familyId") UUID familyId,
                     @Param("now") LocalDateTime now,
                     @Param("revokedStatus") RefreshTokenStatus revokedStatus);

    /** Revoke every live session of a user (logout / password reset / all-token invalidation). */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE RefreshToken t
               SET t.status = :revokedStatus, t.revoked = true, t.revokedAt = :now
             WHERE t.user.id = :userId AND t.revoked = false
            """)
    int revokeAllForUser(@Param("userId") UUID userId,
                         @Param("now") LocalDateTime now,
                         @Param("revokedStatus") RefreshTokenStatus revokedStatus);

    /** Revoke one specific token (best effort on logout, even if the family is unknown). */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE RefreshToken t
               SET t.status = :revokedStatus, t.revoked = true, t.revokedAt = :now
             WHERE t.tokenHash = :tokenHash AND t.revoked = false
            """)
    int revokeByTokenHash(@Param("tokenHash") String tokenHash,
                          @Param("now") LocalDateTime now,
                          @Param("revokedStatus") RefreshTokenStatus revokedStatus);

    void deleteByExpiresAtBefore(LocalDateTime cutoff);
}
