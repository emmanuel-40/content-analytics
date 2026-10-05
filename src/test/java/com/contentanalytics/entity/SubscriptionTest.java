package com.contentanalytics.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Subscription credit accounting")
class SubscriptionTest {

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

        subscription.rollPeriod(SubscriptionTier.PREMIUM.getMonthlyCredits());

        assertThat(subscription.getCreditsUsedThisPeriod()).isZero();
        assertThat(subscription.getMonthlyCreditAllowance()).isEqualTo(100);
        assertThat(subscription.getPurchasedCredits())
                .as("purchased credits must survive the reset - the user paid for them")
                .isEqualTo(50);
        assertThat(subscription.getAvailableCredits()).isEqualTo(150);
    }

    @Test
    @DisplayName("changing tier applies the new allowance")
    void changingTierAppliesNewAllowance() {
        Subscription subscription = subscription(SubscriptionTier.FREE, 5, 0);

        subscription.setTier(SubscriptionTier.ENTERPRISE);
        subscription.rollPeriod(SubscriptionTier.ENTERPRISE.getMonthlyCredits());

        assertThat(subscription.getAvailableCredits()).isEqualTo(1000);
    }

}