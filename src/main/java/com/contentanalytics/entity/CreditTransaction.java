package com.contentanalytics.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * An append-only ledger entry recording a single credit movement.
 * <p>
 * {@code amount} is signed: positive adds credits, negative consumes them.
 * {@code balanceAfter} stores the running balance so history can be replayed and audited
 * without recomputing from every previous row.
 */
@Entity
@Table(name = "credit_transactions", indexes = {
        @Index(name = "idx_credit_tx_user_id", columnList = "user_id"),
        @Index(name = "idx_credit_tx_created_at", columnList = "created_at"),
        @Index(name = "idx_credit_tx_type", columnList = "type")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreditTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private Integer amount;

    @Column(name = "balance_after", nullable = false)
    private Integer balanceAfter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CreditTransactionType type;

    @Column(length = 500)
    private String description;

    /**
     * Correlation id, e.g. the document id for an AI_ANALYSIS charge.
     */
    @Column(name = "reference_id", length = 255)
    private String referenceId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

}