package com.contentanalytics.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One checkout attempt against a payment provider.
 * <p>
 * Created PENDING by {@code POST /api/payments/initialize} before any money
 * moves. Flipped to SUCCESS only by the webhook after HMAC verification and a
 * server-side re-query of the transaction - that flip is the single place
 * credits are granted, which makes duplicate or forged webhooks harmless.
 */
@Entity
@Table(name = "payment_transactions", indexes = {
        @Index(name = "idx_payment_tx_user_id", columnList = "user_id"),
        @Index(name = "idx_payment_tx_reference", columnList = "reference"),
        @Index(name = "idx_payment_tx_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * Our reference, generated as {@code ca-<uuid>} and sent to Paystack as-is.
     * Unique: the webhook lookup and the idempotency guard both key off it.
     */
    @Column(nullable = false, unique = true, length = 100)
    private String reference;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String provider = "PAYSTACK";

    /** Amount charged, in kobo (NGN x 100) - Paystack prices the smallest unit. */
    @Column(name = "amount_kobo", nullable = false)
    private Integer amountKobo;

    /** Credits to grant once the charge confirms. */
    @Column(nullable = false)
    private Integer credits;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PaymentStatus status = PaymentStatus.PENDING;

    /** Paystack checkout URL returned at initialize time. */
    @Column(name = "checkout_url", columnDefinition = "TEXT")
    private String checkoutUrl;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    /** Raw webhook payload kept for forensics on disputes. */
    @Column(name = "raw_webhook", columnDefinition = "TEXT")
    private String rawWebhook;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
