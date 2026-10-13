package com.contentanalytics.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Subscription credit accounting")
class SubscriptionTest {

    // One frozen clock for the whole class: every "now" in these tests comes from this
    // exact instant, so period math is identical on every run regardless of the wall clock.
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 1, 15, 9, 30);
    private static final Clock FIXED_CLOCK = Clock.fixed(
            NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    private Subscription subscription(SubscriptionTier tier, int used, int purchased) {
        return Subscription.builder()
                .tier(tier)
                .monthlyCreditAllowance(tier.getMonthlyCredits())
                .creditsUsedThisPeriod(used)
                .purchasedCredits(purchased)
                .build();
    }

    @Test
    @DisplayName("FREE tier grants 5, PREMIUM 100, ENTERPRISE 1000 monthly credits")
    void tiersHaveExpectedAllowances() {
        assertThat(SubscriptionTier.FREE.getMonthlyCredits()).isEqualTo(5);
        assertThat(SubscriptionTier.PREMIUM.getMonthlyCredits()).isEqualTo(100);
        assertThat(SubscriptionTier.ENTERPRISE.getMonthlyCredits()).isEqualTo(1000);
    }

    @Test
    @DisplayName("available credits = monthly allowance - usage + purchased")
    void availableCreditsCombinesAllowanceAndPurchases() {
        assertThat(subscription(SubscriptionTier.PREMIUM, 40, 0).getAvailableCredits()).isEqualTo(60);
        assertThat(subscription(SubscriptionTier.PREMIUM, 40, 25).getAvailableCredits()).isEqualTo(85);
        assertThat(subscription(SubscriptionTier.FREE, 5, 0).getAvailableCredits()).isZero();
    }

    @Test
    @DisplayName("over-usage never produces a negative balance")
    void availableCreditsNeverGoesNegative() {
        assertThat(subscription(SubscriptionTier.FREE, 99, 0).getAvailableCredits()).isZero();
    }

    @Test
    @DisplayName("rolling the period resets usage but preserves purchased credits")
    void rollPeriodPreservesPurchasedCredits() {
        Subscription subscription = subscription(SubscriptionTier.PREMIUM, 90, 50);

        // Deterministic time: freeze the clock so the new period window is an exact,
        // known value. This is why rollPeriod() takes "now" as a parameter instead of
        // reading the system clock - no wall-clock flakiness, and we can assert the
        // precise boundaries rather than a loose "is after" check.
        LocalDateTime now = LocalDateTime.now(FIXED_CLOCK);
        subscription.rollPeriod(SubscriptionTier.PREMIUM.getMonthlyCredits(), now);

        assertThat(subscription.getCreditsUsedThisPeriod()).isZero();
        assertThat(subscription.getMonthlyCreditAllowance()).isEqualTo(100);
        assertThat(subscription.getPurchasedCredits())
                .as("purchased credits must survive the reset - the user paid for them")
                .isEqualTo(50);
        assertThat(subscription.getAvailableCredits()).isEqualTo(150);
        assertThat(subscription.getPeriodStart()).isEqualTo(now);
        assertThat(subscription.getPeriodEnd())
                .as("a 30-day period measured from the frozen instant")
                .isEqualTo(now.plusDays(30));
    }

    @Test
    @DisplayName("changing tier applies the new allowance")
    void changingTierAppliesNewAllowance() {
        Subscription subscription = subscription(SubscriptionTier.FREE, 5, 0);

        subscription.setTier(SubscriptionTier.ENTERPRISE);
        // Same frozen clock as above; the boundary assertions here only care about
        // the allowance reset, but passing a fixed instant keeps the test deterministic.
        subscription.rollPeriod(SubscriptionTier.ENTERPRISE.getMonthlyCredits(),
                LocalDateTime.now(FIXED_CLOCK));

        assertThat(subscription.getAvailableCredits()).isEqualTo(1000);
    }

}