package com.contentanalytics.repository;

import com.contentanalytics.entity.EmailVerificationToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, String> {

    /**
     * Find token by token string
     */
    Optional<EmailVerificationToken> findByToken(String token);

    /**
     * Find valid (unused and not expired) token by token string
     */
    @Query("SELECT t FROM EmailVerificationToken t WHERE t.token = :token AND t.used = false AND t.expiryDate > :now")
    Optional<EmailVerificationToken> findValidToken(@Param("token") String token, @Param("now") LocalDateTime now);

    /**
     * Find all valid tokens for user
     */
    @Query("SELECT t FROM EmailVerificationToken t WHERE t.user.id = :userId AND t.used = false AND t.expiryDate > :now")
    java.util.List<EmailVerificationToken> findValidTokensForUser(@Param("userId") UUID userId, @Param("now") LocalDateTime now);

    /**
     * Atomic single-use claim: flips {@code used} only while the token is still
     * unused and unexpired. Zero affected rows means another request consumed it
     * (or it expired) after we read it - the caller must reject and invalidate
     * the user's other tokens.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE EmailVerificationToken t
               SET t.used = true, t.verifiedAt = :now
             WHERE t.token = :token AND t.used = false AND t.expiryDate > :now
            """)
    int claimToken(@Param("token") String token, @Param("now") LocalDateTime now);

    /** Invalidate any sibling verification links after one has been consumed. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE EmailVerificationToken t SET t.used = true WHERE t.user.id = :userId AND t.used = false")
    int invalidateAllForUser(@Param("userId") UUID userId);

    /**
     * Delete expired tokens
     */
    void deleteByExpiryDateBefore(LocalDateTime dateTime);

}
