package com.contentanalytics.repository;

import com.contentanalytics.entity.EmailOutbox;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface EmailOutboxRepository extends JpaRepository<EmailOutbox, UUID> {

    /**
     * Insert only when this logical email does not already exist. The unique constraint on
     * dedupe_key is the final authority; the NOT EXISTS guard handles the normal duplicate
     * case without throwing a rollback-only exception inside the caller's transaction.
     *
     * @return 1 when a new outbox row was created, 0 when a duplicate was suppressed
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO email_outbox
                (id, dedupe_key, type, recipient, display_name, subject, html_content,
                 status, attempts, created_at)
            SELECT :id, :dedupeKey, :type, :recipient, :displayName, :subject, :htmlContent,
                   'PENDING', 0, :createdAt
             WHERE NOT EXISTS (
                   SELECT 1 FROM email_outbox WHERE dedupe_key = :dedupeKey
             )
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id,
                       @Param("dedupeKey") String dedupeKey,
                       @Param("type") String type,
                       @Param("recipient") String recipient,
                       @Param("displayName") String displayName,
                       @Param("subject") String subject,
                       @Param("htmlContent") String htmlContent,
                       @Param("createdAt") LocalDateTime createdAt);

    /** Rows old enough that the transaction which created them has certainly committed. */
    @Query("""
            SELECT e FROM EmailOutbox e
             WHERE e.status = 'PENDING'
               AND e.createdAt <= :cutoff
             ORDER BY e.createdAt ASC
            """)
    List<EmailOutbox> findPending(@Param("cutoff") LocalDateTime cutoff, Pageable pageable);

    /**
     * Conditional claim: only one publisher (or one application instance) can move a row
     * from PENDING to PUBLISHED. Zero affected rows means somebody else won the race.
     */
    @Modifying(flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE EmailOutbox e
               SET e.status = 'PUBLISHED', e.publishedAt = :now
             WHERE e.id = :id AND e.status = 'PENDING'
            """)
    int claimForPublish(@Param("id") UUID id, @Param("now") LocalDateTime now);

    /**
     * Conditional send flag: a duplicated broker delivery cannot send the same email twice.
     */
    @Modifying(flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE EmailOutbox e
               SET e.status = 'SENT', e.sentAt = :now
             WHERE e.id = :id AND e.status IN ('PENDING', 'PUBLISHED')
            """)
    int markSent(@Param("id") UUID id, @Param("now") LocalDateTime now);

    /**
     * Record a provider failure and make the row eligible for another attempt. Once the
     * budget is exhausted the row becomes FAILED instead of retrying forever.
     */
    @Modifying(flushAutomatically = true)
    @Transactional
    @Query(value = """
            UPDATE email_outbox
               SET attempts = attempts + 1,
                   last_error = :error,
                   status = CASE WHEN attempts + 1 >= :maxAttempts THEN 'FAILED' ELSE 'PENDING' END,
                   published_at = NULL
             WHERE id = :id AND status = 'PUBLISHED'
            """, nativeQuery = true)
    int recordFailure(@Param("id") UUID id,
                      @Param("error") String error,
                      @Param("maxAttempts") int maxAttempts);

}
