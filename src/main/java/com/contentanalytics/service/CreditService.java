package com.contentanalytics.service;

import com.contentanalytics.dto.CreditTransactionDto;
import com.contentanalytics.dto.SubscriptionDto;
import com.contentanalytics.entity.*;
import com.contentanalytics.exception.InsufficientCreditsException;
import com.contentanalytics.repository.CreditTransactionRepository;
import com.contentanalytics.repository.SubscriptionRepository;
import com.contentanalytics.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Credit accounting for the freemium model.
 * <p>
 * Every balance change writes an immutable ledger row so a user's credit history can be
 * reconstructed and audited. Deductions take a pessimistic row lock on the subscription to
 * stop two concurrent analyses from both spending the same final credit.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditService {

    private final SubscriptionRepository subscriptionRepository;
    private final CreditTransactionRepository creditTransactionRepository;
    private final UserRepository userRepository;

    /**
     * Injectable time source. Spring supplies {@link Clock#systemUTC()} in production; tests
     * swap in {@link Clock#fixed} so period boundaries ({@code now}, {@code now + 30d}) are exact
     * and never race the wall clock.
     */
    private final Clock clock;

    /**
     * Fetch (or lazily create) a user's subscription, rolling the period forward if elapsed.
     */
    @Transactional
    public Subscription getOrCreateSubscription(UUID userId) {
        Subscription subscription = subscriptionRepository.findByUserId(userId)
                .orElseGet(() -> createFreeSubscription(userId));

        if (subscription.getPeriodEnd().isBefore(LocalDateTime.now(clock))) {
            rollPeriod(subscription);
        }

        return subscription;
    }

    private Subscription createFreeSubscription(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        // Read the clock exactly once so periodStart/periodEnd come from a single instant
        // (periodEnd is then precisely start + 30 days, never a couple of microseconds off).
        LocalDateTime now = LocalDateTime.now(clock);
        Subscription subscription = Subscription.builder()
                .user(user)
                .tier(SubscriptionTier.FREE)
                .monthlyCreditAllowance(SubscriptionTier.FREE.getMonthlyCredits())
                .creditsUsedThisPeriod(0)
                .purchasedCredits(0)
                .periodStart(now)
                .periodEnd(now.plusDays(30))
                .status(SubscriptionStatus.ACTIVE)
                .autoRenew(true)
                .build();

        Subscription saved = subscriptionRepository.save(subscription);

        recordTransaction(saved, SubscriptionTier.FREE.getMonthlyCredits(),
                CreditTransactionType.MONTHLY_ALLOCATION,
                "Welcome - free tier monthly allowance", null);

        log.info("Created FREE subscription for user: {}", userId);
        return saved;
    }

    /**
     * Roll the billing period: reset usage and re-grant the monthly allowance.
     * Purchased credits are preserved.
     */
    @Transactional
    public void rollPeriod(Subscription subscription) {
        int allowance = subscription.getTier().getMonthlyCredits();

        log.info("Rolling credit period for user: {} (tier: {})", subscription.getUser().getId(),
                subscription.getTier());

        subscription.rollPeriod(allowance, LocalDateTime.now(clock));
        subscriptionRepository.save(subscription);

        recordTransaction(subscription, allowance, CreditTransactionType.MONTHLY_ALLOCATION,
                "Monthly credit allowance (" + subscription.getTier() + ")", null);
    }

    /**
     * Deduct credits for an AI analysis.
     * <p>
     * Runs in its own transaction so the deduction is committed even if the surrounding
     * document-processing transaction later rolls back.
     *
     * @param documentId the document being analysed, stored on the ledger entry
     * @throws InsufficientCreditsException when the balance is too low
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void consumeCredits(UUID userId, int amount, UUID documentId) {
        // Lock the subscription row so concurrent deductions serialise
        Subscription subscription = subscriptionRepository.findByUserIdForUpdate(userId)
                .orElseGet(() -> createFreeSubscription(userId));

        if (subscription.getPeriodEnd().isBefore(LocalDateTime.now(clock))) {
            rollPeriod(subscription);
        }

        int available = subscription.getAvailableCredits();

        if (available < amount) {
            log.warn("Insufficient credits for user {}: requested {}, available {}", userId, amount, available);
            throw new InsufficientCreditsException(
                    "Insufficient credits. Required: " + amount + ", available: " + available);
        }

        // Consume purchased credits first, then the monthly allowance
        int fromPurchased = Math.min(subscription.getPurchasedCredits(), amount);
        subscription.setPurchasedCredits(subscription.getPurchasedCredits() - fromPurchased);
        subscription.setCreditsUsedThisPeriod(subscription.getCreditsUsedThisPeriod() + (amount - fromPurchased));

        subscriptionRepository.save(subscription);

        recordTransaction(subscription, -amount, CreditTransactionType.AI_ANALYSIS,
                "AI analysis", documentId != null ? documentId.toString() : null);

        log.debug("Consumed {} credit(s) for user {} (remaining: {})", amount, userId,
                subscription.getAvailableCredits());
    }

    /**
     * Add purchased credits (pay-as-you-go). Purchased credits survive the monthly reset.
     */
    @Transactional
    public SubscriptionDto purchaseCredits(UUID userId, int credits, String referenceId) {
        if (credits <= 0) {
            throw new IllegalArgumentException("Credit amount must be positive");
        }

        Subscription subscription = subscriptionRepository.findByUserId(userId)
                .orElseGet(() -> createFreeSubscription(userId));

        subscription.setPurchasedCredits(subscription.getPurchasedCredits() + credits);
        subscriptionRepository.save(subscription);

        recordTransaction(subscription, credits, CreditTransactionType.PURCHASE,
                "Purchased " + credits + " credit(s)", referenceId);

        log.info("User {} purchased {} credit(s)", userId, credits);
        return toDto(subscription);
    }

    /**
     * Change a user's tier, resetting the allowance to the new tier's value.
     */
    @Transactional
    public SubscriptionDto changeTier(UUID userId, SubscriptionTier tier) {
        Subscription subscription = subscriptionRepository.findByUserId(userId)
                .orElseGet(() -> createFreeSubscription(userId));

        subscription.setTier(tier);
        subscription.rollPeriod(tier.getMonthlyCredits(), LocalDateTime.now(clock));
        subscriptionRepository.save(subscription);

        recordTransaction(subscription, tier.getMonthlyCredits(), CreditTransactionType.ADMIN_ADJUSTMENT,
                "Subscription changed to " + tier, null);

        log.info("User {} changed tier to {}", userId, tier);
        return toDto(subscription);
    }

    /**
     * Current subscription status for a user.
     */
    @Transactional(readOnly = true)
    public SubscriptionDto getSubscription(UUID userId) {
        Subscription subscription = subscriptionRepository.findByUserId(userId)
                .orElseGet(() -> createFreeSubscription(userId));

        return toDto(subscription);
    }

    /**
     * Paged credit ledger for a user, newest first.
     */
    @Transactional(readOnly = true)
    public List<CreditTransactionDto> getTransactionHistory(UUID userId, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);

        return creditTransactionRepository
                .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(safePage, safeSize))
                .getContent()
                .stream()
                .map(t -> CreditTransactionDto.builder()
                        .id(t.getId())
                        .amount(t.getAmount())
                        .balanceAfter(t.getBalanceAfter())
                        .type(t.getType().name())
                        .description(t.getDescription())
                        .referenceId(t.getReferenceId())
                        .createdAt(t.getCreatedAt())
                        .build())
                .toList();
    }

    /**
     * Whether a user may run an AI analysis right now. Does not deduct.
     */
    @Transactional(readOnly = true)
    public boolean hasCredits(UUID userId) {
        Subscription subscription = subscriptionRepository.findByUserId(userId).orElse(null);
        return subscription != null && subscription.getAvailableCredits() > 0;
    }

    /**
     * Append a ledger entry capturing the balance after the movement.
     */
    private void recordTransaction(Subscription subscription, int amount,
                                   CreditTransactionType type, String description, String referenceId) {
        CreditTransaction transaction = CreditTransaction.builder()
                .user(subscription.getUser())
                .amount(amount)
                .balanceAfter(subscription.getAvailableCredits())
                .type(type)
                .description(description)
                .referenceId(referenceId)
                .build();

        creditTransactionRepository.save(transaction);
    }

    private SubscriptionDto toDto(Subscription subscription) {
        return SubscriptionDto.builder()
                .tier(subscription.getTier().name())
                .status(subscription.getStatus().name())
                .monthlyCreditAllowance(subscription.getMonthlyCreditAllowance())
                .creditsUsedThisPeriod(subscription.getCreditsUsedThisPeriod())
                .purchasedCredits(subscription.getPurchasedCredits())
                .availableCredits(subscription.getAvailableCredits())
                .periodStart(subscription.getPeriodStart())
                .periodEnd(subscription.getPeriodEnd())
                .autoRenew(subscription.getAutoRenew())
                .build();
    }

}