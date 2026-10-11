package com.contentanalytics.entity;

/**
 * Lifecycle of a checkout attempt.
 * <p>
 * The only legal transition that grants credits is PENDING -&gt; SUCCESS, and it
 * may only be performed by the webhook handler after the provider confirms the
 * charge server-side. The frontend callback can never move a row to SUCCESS.
 */
public enum PaymentStatus {

    /** Checkout created, user has not paid yet. */
    PENDING,

    /** Webhook verified + provider re-query confirmed paid. Credits granted. */
    SUCCESS,

    /** Provider reports the charge failed. Terminal, no credits. */
    FAILED,

    /** Checkout expired or user abandoned it. Terminal, no credits. */
    ABANDONED
}
