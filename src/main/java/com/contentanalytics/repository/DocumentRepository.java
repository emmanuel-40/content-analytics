package com.contentanalytics.repository;

import com.contentanalytics.entity.Document;
import com.contentanalytics.entity.DocumentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocumentRepository extends JpaRepository<Document, UUID> {

    /**
     * Find all documents for a specific user with pagination
     */
    Page<Document> findByUserId(UUID userId, Pageable pageable);

    /**
     * Find all documents for a user with a specific status
     */
    Page<Document> findByUserIdAndStatus(UUID userId, DocumentStatus status, Pageable pageable);

    /**
     * Find documents created in date range for a user
     */
    @Query("SELECT d FROM Document d WHERE d.user.id = :userId AND d.createdAt BETWEEN :startDate AND :endDate")
    Page<Document> findByUserIdAndDateRange(
            @Param("userId") UUID userId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            Pageable pageable
    );

    /**
     * Search documents by file name
     */
    @Query("SELECT d FROM Document d WHERE d.user.id = :userId AND LOWER(d.fileName) LIKE LOWER(CONCAT('%', :fileName, '%'))")
    Page<Document> searchByFileName(
            @Param("userId") UUID userId,
            @Param("fileName") String fileName,
            Pageable pageable
    );

    /**
     * Find documents currently being processed
     */
    Page<Document> findByStatus(DocumentStatus status, Pageable pageable);

    /**
     * Find failed documents (for retry logic)
     */
    List<Document> findByStatusAndProcessingErrorIsNotNull(DocumentStatus status);

    /**
     * Count documents by status for a user
     */
    long countByUserIdAndStatus(UUID userId, DocumentStatus status);

    /**
     * Get total documents for a user
     */
    long countByUserId(UUID userId);

    /**
     * Find document by ID and verify ownership
     */
    @Query("SELECT d FROM Document d WHERE d.id = :documentId AND d.user.id = :userId")
    Optional<Document> findByIdAndUserId(
            @Param("documentId") UUID documentId,
            @Param("userId") UUID userId
    );

    /**
     * Row-locked read used for every state transition. Combined with the status guard in
     * the caller this makes "check then update" atomic across workers.
     */
    @Query("SELECT d FROM Document d WHERE d.id = :documentId")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Document> findByIdForUpdate(@Param("documentId") UUID documentId);

    /**
     * Atomic claim: only a worker that sees UPLOADED under the row lock gets the document.
     * A second worker blocks on the lock and then finds no matching row once the first
     * worker has committed PROCESSING.
     */
    @Query("SELECT d FROM Document d WHERE d.id = :documentId AND d.status = :status")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Document> findByIdAndStatusForUpdate(
            @Param("documentId") UUID documentId,
            @Param("status") DocumentStatus status
    );

    /**
     * Owned, row-locked read used by deletion so a worker cannot claim a document
     * between the status check and the delete.
     */
    @Query("SELECT d FROM Document d WHERE d.id = :documentId AND d.user.id = :userId")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Document> findByIdAndUserIdForUpdate(
            @Param("documentId") UUID documentId,
            @Param("userId") UUID userId
    );

    /**
     * Put a claimed document back into the retryable UPLOADED state. Uses a bulk update
     * with an explicit version bump so it also works from the afterCommit hook (where no
     * managed entity is available) and cannot lose a concurrent update.
     *
     * @return number of rows actually reset (0 means another worker already moved on)
     */
    @Modifying(flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE Document d
               SET d.status = :toStatus,
                   d.processingProgress = 0F,
                   d.processingError = :error,
                   d.updatedAt = :updatedAt,
                   d.version = d.version + 1L
             WHERE d.id = :documentId
               AND d.status IN :fromStatuses
            """)
    int resetClaimedDocument(
            @Param("documentId") UUID documentId,
            @Param("fromStatuses") Collection<DocumentStatus> fromStatuses,
            @Param("toStatus") DocumentStatus toStatus,
            @Param("error") String error,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    /**
     * Find documents with specific sentiment
     */
    @Query("SELECT d FROM Document d WHERE d.user.id = :userId AND d.sentiment = :sentiment")
    Page<Document> findBySentiment(
            @Param("userId") UUID userId,
            @Param("sentiment") String sentiment,
            Pageable pageable
    );

    /**
     * Find documents containing specific topic
     */
    @Query("SELECT d FROM Document d WHERE d.user.id = :userId AND d.keyTopics LIKE CONCAT('%', :topic, '%')")
    Page<Document> findByTopic(
            @Param("userId") UUID userId,
            @Param("topic") String topic,
            Pageable pageable
    );

    /**
     * Get statistics for a user
     */
    // CORRECT - Use COALESCE to handle NULL:
    @Query(value = """
    SELECT
        COUNT(*),
        COALESCE(SUM(CASE WHEN status = 'PROCESSING' THEN 1 ELSE 0 END), 0),
        COALESCE(SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END), 0),
        COALESCE(SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END), 0),
        COALESCE(SUM(word_count), 0)
    FROM documents
    WHERE user_id = ?1
""", nativeQuery = true)
    Object[] getUserDocumentStats(UUID userId);

    /**
     * Completed documents whose source file may still exist in storage. The worker
     * normally deletes it immediately; this query is the retry path for that cleanup.
     */
    @Query("""
            SELECT d FROM Document d
             WHERE d.status = :status
               AND d.sourceFileDeleted = FALSE
               AND d.processingCompletedAt < :cutoff
             ORDER BY d.processingCompletedAt ASC
            """)
    List<Document> findSourceCleanupCandidates(
            @Param("status") DocumentStatus status,
            @Param("cutoff") LocalDateTime cutoff,
            Pageable pageable);

    /**
     * Mark a source file as removed. Guarded by COMPLETED so a stale cleanup cannot
     * change a document that was reopened or deleted.
     *
     * @return number of rows updated (0 means the document is no longer eligible)
     */
    @Modifying(flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE Document d
               SET d.sourceFileDeleted = TRUE,
                   d.updatedAt = :updatedAt
             WHERE d.id = :documentId
               AND d.status = :status
               AND d.sourceFileDeleted = FALSE
            """)
    int markSourceFileDeleted(
            @Param("documentId") UUID documentId,
            @Param("status") DocumentStatus status,
            @Param("updatedAt") LocalDateTime updatedAt);
}