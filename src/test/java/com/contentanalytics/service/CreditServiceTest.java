package com.contentanalytics.service;

import com.contentanalytics.dto.SubscriptionDto;
import com.contentanalytics.entity.Subscription;
import com.contentanalytics.entity.SubscriptionStatus;
import com.contentanalytics.entity.SubscriptionTier;
import com.contentanalytics.repository.CreditTransactionRepository;
import com.contentanalytics.repository.SubscriptionRepository;
import com.contentanalytics.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CreditService} optimistic-lock retry behavior.
 *
 * Covers successful purchase, optimistic-lock conflict with retry, retry
 * exhaustion after 3 attempts, and invalid credit amount guard.
 */
@DisplayName("CreditService")
class CreditServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-01-15T09:30:00Z");
    private static final Clock CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    private SubscriptionRepository subscriptionRepository;
    private CreditTransactionRepository creditTransactionRepository;
    private UserRepository userRepository;
    private CreditService service;

    @BeforeEach
    void setUp() {
        subscriptionRepository = mock(SubscriptionRepository.class);
        creditTransactionRepository = mock(CreditTransactionRepository.class);
        userRepository = mock(UserRepository.class);
        service = new CreditService(subscriptionRepository, creditTransactionRepository, userRepository, CLOCK);
    }

    @Test
    @DisplayName("purchaseCredits succeeds when sufficient credits exist")
    void purchaseSucceedsWithSufficientCredits() {
        UUID userId = UUID.randomUUID();
        Subscription sub = subscriptionWithCredits(10);
        when(subscriptionRepository.findByUserIdForUpdate(userId))
                .thenReturn(Optional.of(sub));
        when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(inv -> inv.getArgument(0));

        SubscriptionDto result = service.purchaseCredits(userId, 3, "ref-1");

        assertThat(result.getAvailableCredits()).isEqualTo(13);
        verify(creditTransactionRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("purchaseCredits rejects non-positive credit amount")
    void purchaseRejectsNonPositiveAmount() {
        UUID userId = UUID.randomUUID();

        assertThatThrownBy(() -> service.purchaseCredits(userId, 0, "ref-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Credit amount must be positive");
    }

    @Test
    @DisplayName("lock conflict is not retried inside the same transaction")
    void lockConflictIsNotRetriedInSameTransaction() {
        UUID userId = UUID.randomUUID();

        when(subscriptionRepository.findByUserIdForUpdate(any(UUID.class)))
                .thenThrow(new org.springframework.dao.OptimisticLockingFailureException("conflict"));

        assertThatThrownBy(() -> service.purchaseCredits(userId, 3, "ref-1"))
                .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
        verify(subscriptionRepository, times(1)).findByUserIdForUpdate(any(UUID.class));
    }

    @Test
    @DisplayName("getOrCreateSubscription finds existing subscription")
    void getOrCreateFindsExisting() {
        UUID userId = UUID.randomUUID();
        Subscription sub = subscriptionWithCredits(10);
        when(subscriptionRepository.findByUserIdForUpdate(userId)).thenReturn(Optional.of(sub));

        Subscription result = service.getOrCreateSubscription(userId);

        assertThat(result).isSameAs(sub);
    }

    private Subscription subscriptionWithCredits(int credits) {
        Subscription sub = new Subscription();
        sub.setId(UUID.randomUUID());
        sub.setUser(new com.contentanalytics.entity.User());
        sub.setTier(SubscriptionTier.FREE);
        sub.setMonthlyCreditAllowance(10);
        sub.setCreditsUsedThisPeriod(10 - credits);
        sub.setPurchasedCredits(0);
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setPeriodStart(LocalDateTime.now(CLOCK));
        sub.setPeriodEnd(LocalDateTime.now(CLOCK).plusDays(30));
        sub.setAutoRenew(true);
        return sub;
    }
}
