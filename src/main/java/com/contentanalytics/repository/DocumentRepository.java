package com.contentanalytics.repository;

import com.contentanalytics.entity.Document;
import com.contentanalytics.entity.DocumentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
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
    // ✅ CORRECT - Use COALESCE to handle NULL:
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
}