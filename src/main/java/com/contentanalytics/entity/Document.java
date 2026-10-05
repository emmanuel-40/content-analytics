package com.contentanalytics.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "documents", indexes = {
        @Index(name = "idx_user_id", columnList = "user_id"),
        @Index(name = "idx_status", columnList = "status"),
        @Index(name = "idx_created_at", columnList = "created_at"),
        @Index(name = "idx_file_name", columnList = "file_name")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 500)
    private String fileName;

    @Column(nullable = false)
    private String fileKey; // S3 or storage key

    @Column(nullable = false)
    private Long fileSize; // in bytes

    @Column(nullable = false, length = 50)
    private String mimeType; // application/pdf, text/plain, etc.

    @Column(nullable = false)
    private Integer pageCount; // For PDFs

    @Column(length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentStatus status; // UPLOADED, PROCESSING, COMPLETED, FAILED

    @Column
    private Float processingProgress; // 0-100%

    @Column(columnDefinition = "TEXT")
    private String processingError; // Error message if processing failed

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Column
    private LocalDateTime processingStartedAt;

    @Column
    private LocalDateTime processingCompletedAt;

    // Metadata after processing
    @Column(columnDefinition = "TEXT")
    private String extractedText; // Raw text from document

    @Column
    private Integer wordCount;

    @Column
    private Integer characterCount;

    @Column(columnDefinition = "TEXT")
    private String summary; // AI-generated summary

    @Column(columnDefinition = "TEXT")
    private String keyTopics; // JSON array of key topics

    @Column(length = 50)
    private String sentiment; // POSITIVE, NEUTRAL, NEGATIVE

    @Column
    private Float confidenceScore; // For AI analysis confidence

}
