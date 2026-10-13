package com.contentanalytics.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A user's subscription tier and their credit allowance for the current period.
 * 
 * One row per user ({@code user_id} is unique). Purchased credits that exceed the monthly
 * allowance are tracked separately in {@link #purchasedCredits}, so rolling the period over
 * never destroys money the user paid for.
 */
@Entity
@Table(name = "subscriptions", uniqueConstraints = {
        @UniqueConstraint(name = "uk_subscription_user", columnNames = "user_id")
}, indexes = {
        @Index(name = "idx_subscription_tier", columnList = "tier"),
        @Index(name = "idx_subscription_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SubscriptionTier tier;

    @Column(name = "monthly_credit_allowance", nullable = false)
    private Integer monthlyCreditAllowance;

    @Column(name = "credits_used_this_period", nullable = false)
    private Integer creditsUsedThisPeriod;

    /**
     * Credits bought with money. These survive the monthly reset.
     */
    @Column(name = "purchased_credits", nullable = false)
    @Builder.Default
    private Integer purchasedCredits = 0;

    @Column(name = "period_start", nullable = false)
    private LocalDateTime periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDateTime periodEnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SubscriptionStatus status;

    @Column(name = "auto_renew", nullable = false)
    private Boolean autoRenew;

    @Column(name = "external_customer_id", length = 255)
    private String externalCustomerId;

    @Column(name = "external_subscription_id", length = 255)
    private String externalSubscriptionId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * @return credits still available this period (monthly allowance minus usage,
     *         plus any purchased credits).
     */
    public int getAvailableCredits() {
        int monthlyRemaining = Math.max(0, monthlyCreditAllowance - creditsUsedThisPeriod);
        return monthlyRemaining + purchasedCredits;
    }

    /**
     * Roll the billing period forward, resetting the monthly allowance.
     * <p>
     * {@code purchasedCredits} are intentionally preserved.
     *
     * @param now the current instant, passed in by the caller rather than read
     *            from the system clock here. Injecting the time source keeps this
     *            deterministic in tests (a fixed {@link java.time.Clock} can be supplied)
     *            and guarantees {@code periodStart}/{@code periodEnd} come from one instant.
     */
    public void rollPeriod(int newAllowance, LocalDateTime now) {
        this.monthlyCreditAllowance = newAllowance;
        this.creditsUsedThisPeriod = 0;
        this.periodStart = now;
        this.periodEnd = now.plusDays(30);
    }

}