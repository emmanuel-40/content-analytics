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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Credit accounting for the freemium model.
 *
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
     * Optional in unit tests (which construct this service directly). In the
     * application Spring supplies it, allowing the free-subscription insert to
     * run in its own transaction so a unique-constraint race can be retried.
     */
    @Autowired(required = false)
    private PlatformTransactionManager transactionManager;

    /**
     * Fetch (or lazily create) a user's subscription, rolling the period forward if elapsed.
     *
     * Uses {@code SELECT ... FOR UPDATE} so concurrent calls for the same user serialize
     * on the subscription row. If two requests both find no row, only one creates it;
     * the other hits the unique constraint on {@code user_id} and re-reads the existing row.
     */
    @Transactional
    public Subscription getOrCreateSubscription(UUID userId) {
        Subscription subscription = subscriptionRepository.findByUserIdForUpdate(userId)
                .orElse(null);

        if (subscription == null) {
            createFreeSubscriptionIdempotent(userId);
            // Re-read in THIS transaction so the returned entity is managed and
            // the row is locked before any balance change.
            subscription = subscriptionRepository.findByUserIdForUpdate(userId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Subscription race: neither created nor found for user " + userId));
        }

        if (subscription.getStatus() == SubscriptionStatus.ACTIVE
                && subscription.getPeriodEnd().isBefore(LocalDateTime.now(clock))) {
            rollPeriod(subscription);
        }

        return subscription;
    }

    /**
     * Create the free subscription in a disposable REQUIRES_NEW transaction.
     * PostgreSQL aborts a transaction on a unique violation, so the recovery
     * re-read must happen in a fresh transaction — never after the violation
     * inside the caller's transaction.
     */
    private void createFreeSubscriptionIdempotent(UUID userId) {
        if (transactionManager == null) {
            // Unit tests without a Spring context: single-threaded convenience.
            createFreeSubscription(userId);
            return;
        }
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            template.executeWithoutResult(status -> createFreeSubscription(userId));
        } catch (DataIntegrityViolationException e) {
            log.debug("Concurrent subscription creation for user {} — reusing existing row", userId);
        }
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

        // flush inside the REQUIRES_NEW block so a unique violation is caught
        // there and the caller's transaction is never marked rollback-only.
        Subscription saved = subscriptionRepository.saveAndFlush(subscription);

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
        if (subscription.getStatus() != SubscriptionStatus.ACTIVE) {
            log.debug("Skipping rollover for non-active subscription of user {}",
                    subscription.getUser().getId());
            return;
        }
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
     *
     * Runs in its own transaction so the deduction is committed even if the surrounding
     * document-processing transaction later rolls back.
     *
     * @param documentId the document being analysed, stored on the ledger entry
     * @throws InsufficientCreditsException when the balance is too low
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void consumeCredits(UUID userId, int amount, UUID documentId) {
        // Lock the subscription row FIRST. Two duplicate workers for the same
        // document both reach this point; the row lock serialises them.
        Subscription subscription = subscriptionRepository.findByUserIdForUpdate(userId)
                .orElse(null);
        if (subscription == null) {
            createFreeSubscriptionIdempotent(userId);
            subscription = subscriptionRepository.findByUserIdForUpdate(userId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Subscription race: neither created nor found for user " + userId));
        }

        // Re-check UNDER the lock: the pre-lock check alone is a TOCTOU.
        if (documentId != null
                && creditTransactionRepository.existsByReferenceIdAndType(
                        documentId.toString(), CreditTransactionType.AI_ANALYSIS)) {
            log.info("Credits already consumed for document {} — skipping debit", documentId);
            return;
        }

        if (subscription.getStatus() == SubscriptionStatus.ACTIVE
                && subscription.getPeriodEnd().isBefore(LocalDateTime.now(clock))) {
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
     *
     * Takes a pessimistic write lock on the subscription row so concurrent purchases
     * for the same user serialize. The DB unique index on
     * {@code credit_transactions(reference_id)} is the final dedupe guard.
     */
    @Transactional
    public SubscriptionDto purchaseCredits(UUID userId, int credits, String referenceId) {
        if (credits <= 0) {
            throw new IllegalArgumentException("Credit amount must be positive");
        }

        Subscription subscription = subscriptionRepository.findByUserIdForUpdate(userId)
                .orElse(null);
        if (subscription == null) {
            createFreeSubscriptionIdempotent(userId);
            subscription = subscriptionRepository.findByUserIdForUpdate(userId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Subscription race: neither created nor found for user " + userId));
        }

        // Re-check under the lock. Same payment reference never credits twice.
        if (referenceId != null && !referenceId.isBlank()
                && creditTransactionRepository.existsByReferenceIdAndType(
                        referenceId, CreditTransactionType.PURCHASE)) {
            log.info("Purchase reference {} already credited — returning current balance", referenceId);
            return toDto(subscription);
        }

        subscription.setPurchasedCredits(subscription.getPurchasedCredits() + credits);
        subscriptionRepository.save(subscription);

        recordTransaction(subscription, credits, CreditTransactionType.PURCHASE,
                "Purchased " + credits + " credit(s)", referenceId);

        log.info("User {} purchased {} credit(s)", userId, credits);
        return toDto(subscription);
    }

    /**
     * Change a user's tier, resetting the allowance to the new tier's value.
     *
     * Takes a pessimistic write lock on the subscription row so concurrent tier changes
     * for the same user serialize. Re-applying the same tier is a no-op: it must not
     * mint another monthly allowance.
     */
    @Transactional
    public SubscriptionDto changeTier(UUID userId, SubscriptionTier tier) {
        Subscription subscription = subscriptionRepository.findByUserIdForUpdate(userId)
                .orElse(null);
        if (subscription == null) {
            createFreeSubscriptionIdempotent(userId);
            subscription = subscriptionRepository.findByUserIdForUpdate(userId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Subscription race: neither created nor found for user " + userId));
        }

        if (subscription.getTier() == tier) {
            log.debug("User {} already on tier {} — no allowance change", userId, tier);
            return toDto(subscription);
        }

        subscription.setTier(tier);
        subscription.rollPeriod(tier.getMonthlyCredits(), LocalDateTime.now(clock));
        subscriptionRepository.save(subscription);

        recordTransaction(subscription, tier.getMonthlyCredits(), CreditTransactionType.ADMIN_ADJUSTMENT,
                "Subscription changed to " + tier, null);

        log.info("User {} changed tier to {}", userId, tier);
        return toDto(subscription);
    }

    /**
     * Current subscription status for a user (creates the free tier row if missing).
     */
    @Transactional
    public SubscriptionDto getSubscription(UUID userId) {
        return toDto(getOrCreateSubscription(userId));
    }

    /**
     * Cheap pre-flight check before sending document text to a paid provider. This is not an
     * authorization decision: consumeCredits re-checks under a row lock and remains the
     * authoritative debit.
     */
    @Transactional
    public boolean hasAvailableCredits(UUID userId, int amount) {
        if (amount <= 0) {
            return true;
        }
        return getSubscription(userId).getAvailableCredits() >= amount;
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
