package com.contentanalytics.service.payment;

import java.util.Map;

/**
 * Payment gateway abstraction.
 * <p>
 * Today Paystack is the only implementation. The interface exists so the
 * checkout flow ({@code PaymentService}) never touches provider HTTP details
 * directly - a second provider later is a new class, not a rewrite. The test
 * suite also implements this interface with a scripted stub (no network), so
 * the money-safety rules are asserted without calling Paystack.
 * <p>
 * Implementations must never grant credits themselves - they report what the
 * provider said, and {@code PaymentService} decides what to persist.
 */
public interface PaymentProvider {

    /** Stable name stored on {@code payment_transactions.provider}. */
    String getName();

    /**
     * Create a checkout on the provider side.
     *
     * @param email       payer email (Paystack requires it)
     * @param amountKobo  amount in kobo (NGN x 100)
     * @param reference   our unique reference, sent to the provider as-is
     * @param callbackUrl where the provider sends the browser after payment
     *                    (UX only - never trusted for crediting)
     * @return provider checkout URL + access code for the response DTO
     */
    InitializeResult initialize(String email, int amountKobo, String reference, String callbackUrl);

    /**
     * Server-side re-query of a transaction. Called by the webhook handler
     * before anything is persisted - the webhook body alone is never trusted.
     *
     * @param reference our reference echoed back by the provider
     * @return authoritative status + paid amount, or empty when unknown
     */
    VerifyResult verify(String reference);

    /**
     * Verify the webhook signature using the provider secret.
     *
     * @param rawBody  exact request bytes as received
     * @param signature value of the provider signature header
     * @return true when the payload genuinely came from the provider
     */
    boolean verifyWebhookSignature(String rawBody, String signature);

    /**
     * Extract our reference from a webhook payload. Empty when the event is
     * not a charge event we act on.
     */
    java.util.Optional<String> extractReference(Map<String, Object> payload);

    record InitializeResult(String checkoutUrl, String accessCode) {}

    record VerifyResult(boolean paid, int amountKobo, String paidAt) {}
}
