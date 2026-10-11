package com.contentanalytics.service.payment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * No-network stand-in used when no provider keys are configured locally.
 * <p>
 * Returns a fake checkout URL that points back at the API itself, and verifies
 * its own references as unpaid - so the happy path (initialize -&gt; PENDING row
 * -&gt; {@code /api/auth/providers}-style capability flag) can be exercised
 * with zero credentials and zero network. The unit tests drive webhooks past
 * this class with a stub, so production Paystack behavior is asserted without
 * ever calling Paystack.
 */
@Service
@Slf4j
public class MockPaymentProvider implements PaymentProvider {

    @Override
    public String getName() {
        return "MOCK";
    }

    @Override
    public InitializeResult initialize(String email, int amountKobo, String reference, String callbackUrl) {
        log.info("Mock checkout for {} ({} kobo, ref {})", email, amountKobo, reference);
        return new InitializeResult(callbackUrl + "?reference=" + reference + "&mock=true", "mock-access");
    }

    @Override
    public VerifyResult verify(String reference) {
        return new VerifyResult(false, 0, null);
    }

    @Override
    public boolean verifyWebhookSignature(String rawBody, String signature) {
        return "mock-signature".equals(signature);
    }

    @Override
    public Optional<String> extractReference(Map<String, Object> payload) {
        Object ref = payload.get("reference");
        return ref == null ? Optional.empty() : Optional.of(ref.toString());
    }
}
