package com.contentanalytics.entity;

/**
 * Subscription tier.
 * <p>
 * Each tier grants a monthly credit allowance that gates how many AI analyses a user may run.
 */
public enum SubscriptionTier {

    /**
     * Basic (non-AI) analysis only: word count, character count, page count.
     * Carries a small monthly credit allowance for occasional AI summaries.
     */
    FREE(5, "Free tier"),

    /**
     * Larger monthly credit allowance for AI summaries, topics and sentiment.
     */
    PREMIUM(100, "Premium tier"),

    /**
     * Highest monthly credit allowance, intended for heavy usage.
     */
    ENTERPRISE(1000, "Enterprise tier");

    private final int monthlyCredits;
    private final String label;

    SubscriptionTier(int monthlyCredits, String label) {
        this.monthlyCredits = monthlyCredits;
        this.label = label;
    }

    public int getMonthlyCredits() {
        return monthlyCredits;
    }

    public String getLabel() {
        return label;
    }

    /**
     * @return true if this tier is allowed to run AI analysis at all.
     */
    public boolean allowsAiAnalysis() {
        return this != FREE || monthlyCredits > 0;
    }

}