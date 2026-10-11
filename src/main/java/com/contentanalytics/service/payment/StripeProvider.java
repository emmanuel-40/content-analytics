package com.contentanalytics.service.payment;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * Stripe placeholder.
 * <p>
 * Intentionally unimplemented: a Nigerian business cannot open a Stripe
 * account without a US/UK/EU entity, so Paystack is the live provider. When
 * that changes, implement the four methods here (Checkout Session +
 * {@code checkout.session.completed} webhook) - {@code PaymentService} picks
 * providers by name and needs no changes.
 */
@Service
public class StripeProvider implements PaymentProvider {

    @Override
    public String getName() {
        return "STRIPE";
    }

    @Override
    public InitializeResult initialize(String email, int amountKobo, String reference, String callbackUrl) {
        throw new UnsupportedOperationException("Stripe is not enabled. Set app.payments.provider=paystack.");
    }

    @Override
    public VerifyResult verify(String reference) {
        throw new UnsupportedOperationException("Stripe is not enabled.");
    }

    @Override
    public boolean verifyWebhookSignature(String rawBody, String signature) {
        return false;
    }

    @Override
    public Optional<String> extractReference(Map<String, Object> payload) {
        return Optional.empty();
    }
}
