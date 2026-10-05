package com.contentanalytics.entity;

/**
 * Reason for a credit ledger entry.
 */
public enum CreditTransactionType {

    /** Credits granted at the start of a billing period. */
    MONTHLY_ALLOCATION,

    /** Pay-as-you-go credits purchased by the user. */
    PURCHASE,

    /** Credits consumed by running an AI document analysis. */
    AI_ANALYSIS,

    /** Credits returned, e.g. when an analysis failed after deduction. */
    REFUND,

    /** Manual adjustment by an administrator. */
    ADMIN_ADJUSTMENT,

    /** Credits removed because they expired at the end of a period. */
    EXPIRY

}