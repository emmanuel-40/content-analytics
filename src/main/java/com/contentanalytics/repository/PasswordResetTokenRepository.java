package com.contentanalytics.repository;

import com.contentanalytics.entity.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, String> {

    /**
     * Find token by token string
     */
    Optional<PasswordResetToken> findByToken(String token);

    /**
     * Find valid (unused and not expired) token
     */
    @Query("SELECT t FROM PasswordResetToken t WHERE t.token = :token AND t.used = false AND t.expiryDate > :now")
    Optional<PasswordResetToken> findValidToken(@Param("token") String token, @Param("now") LocalDateTime now);

    /**
     * Find latest reset token for user
     */
    @Query("SELECT t FROM PasswordResetToken t WHERE t.user.id = :userId ORDER BY t.createdAt DESC LIMIT 1")
    Optional<PasswordResetToken> findLatestForUser(@Param("userId") String userId);

    /**
     * Atomic single-use claim: the UPDATE only succeeds while the row is unused
     * and unexpired, so a concurrent second use sees zero affected rows.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE PasswordResetToken t
               SET t.used = true, t.usedAt = :now
             WHERE t.token = :token AND t.used = false AND t.expiryDate > :now
            """)
    int claimToken(@Param("token") String token, @Param("now") LocalDateTime now);

    /** Invalidate every other outstanding reset link for the user. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE PasswordResetToken t SET t.used = true, t.usedAt = :now WHERE t.user.id = :userId AND t.used = false")
    int invalidateAllForUser(@Param("userId") UUID userId, @Param("now") LocalDateTime now);

    /**
     * Delete expired tokens
     */
    void deleteByExpiryDateBefore(LocalDateTime dateTime);

}
