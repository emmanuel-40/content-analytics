package com.contentanalytics.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * transactional outbox for email.
 *
 * The row is written in the SAME database transaction as the user/token that caused the
 * email, so a committed state change can never lose its notification. A publisher later
 * moves the row to RabbitMQ (or the in-process fallback), and the consumer marks it SENT.
 *
 * dedupeKey is unique: two requests for the same logical email collapse into one row,
 * and a duplicated broker delivery is a no-op.
 */
@Entity
@Table(name = "email_outbox", indexes = {
        @Index(name = "idx_email_outbox_status_created", columnList = "status, created_at"),
        @Index(name = "idx_email_outbox_dedupe_key", columnList = "dedupe_key", unique = true)
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "dedupe_key", nullable = false, unique = true, length = 255)
    private String dedupeKey;

    @Column(name = "type", nullable = false, length = 50)
    private String type;

    @Column(name = "recipient", nullable = false, length = 255)
    private String recipient;

    @Column(name = "display_name", length = 255)
    private String displayName;

    @Column(name = "subject", length = 255)
    private String subject;

    @Column(name = "html_content", length = 65535, columnDefinition = "TEXT")
    private String htmlContent;

    /** PENDING -> PUBLISHED -> SENT, or FAILED after the retry budget is exhausted. */
    @Builder.Default
    @Column(name = "status", nullable = false, length = 20)
    private String status = "PENDING";

    @Builder.Default
    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

}
