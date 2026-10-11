package com.contentanalytics.service.payment;

import com.contentanalytics.entity.*;
import com.contentanalytics.repository.PaymentTransactionRepository;
import com.contentanalytics.repository.UserRepository;
import com.contentanalytics.service.CreditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Checkout orchestration: initialize, webhook, verify, refund.
 * <p>
 * The invariant everything here protects: <b>credits move if and only if the
 * webhook path flips a PENDING row to SUCCESS after HMAC verification and a
 * server-side re-query</b>. The browser callback, the frontend, and repeated
 * webhook deliveries can never mint credits on their own.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    /** Price list: credits -&gt; kobo. Single place so API, webhook and tests agree. */
    public static final Map<Integer, Integer> CREDIT_PACKS = Map.of(
            10, 100_000,    // 10 credits = NGN 1,000
            50, 400_000,    // 50 credits = NGN 4,000 (save 20%)
            120, 800_000);  // 120 credits = NGN 8,000 (save 33%)

    private final PaymentTransactionRepository paymentRepository;
    private final UserRepository userRepository;
    private final CreditService creditService;
    private final List<PaymentProvider> providers;

    @Value("${app.payments.provider:paystack}")
    private String configuredProvider;

    @Value("${app.payments.callback-url:http://localhost:5173/payment/callback}")
    private String callbackUrl;

    // ------------------------------------------------------------------
    // Initialize
    // ------------------------------------------------------------------

    /**
     * Start a checkout: validate the pack, persist a PENDING row, then ask the
     * provider for a checkout URL. The row exists before any money moves, so a
     * webhook that arrives before our response is still matchable.
     */
    @Transactional
    public PaymentTransaction initializeCheckout(UUID userId, int credits) {
        Integer amountKobo = CREDIT_PACKS.get(credits);
        if (amountKobo == null) {
            throw new IllegalArgumentException(
                    "Unknown credit pack '" + credits + "'. Available: " + CREDIT_PACKS.keySet());
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        String reference = "ca-" + UUID.randomUUID();
        PaymentProvider provider = activeProvider();

        PaymentTransaction tx = PaymentTransaction.builder()
                .user(user)
                .reference(reference)
                .provider(provider.getName())
                .amountKobo(amountKobo)
                .credits(credits)
                .status(PaymentStatus.PENDING)
                .build();
        tx = paymentRepository.save(tx);

        PaymentProvider.InitializeResult checkout =
                provider.initialize(user.getEmail(), amountKobo, reference, callbackUrl);
        tx.setCheckoutUrl(checkout.checkoutUrl());
        tx = paymentRepository.save(tx);

        log.info("Checkout {} opened for user {} ({} credits, {} kobo)",
                reference, userId, credits, amountKobo);
        return tx;
    }
    // ------------------------------------------------------------------
    // Webhook (the ONLY path that grants credits)
    // ------------------------------------------------------------------

    /**
     * Apply a provider webhook. Safe to call repeatedly and safe to call with
     * forged bodies: unknown/decorated payloads are logged and ignored, and a
     * reference that is already SUCCESS is acknowledged without touching credits.
     *
     * @param rawBody exact request bytes (HMAC is computed over these, not over
     *                re-serialized JSON - whitespace differences break the digest)
     * @param signature value of the x-paystack-signature header
     * @return the stored reference, or empty when the payload was ignored
     */
    @Transactional
    public java.util.Optional<String> handleWebhook(String rawBody, String signature) {
        PaymentProvider provider = activeProvider();

        if (!provider.verifyWebhookSignature(rawBody, signature)) {
            log.warn("Rejected webhook with bad signature (provider {})", provider.getName());
            return java.util.Optional.empty();
        }

        Map<String, Object> payload;
        try {
            payload = new com.fasterxml.jackson.databind.ObjectMapper().readValue(rawBody, Map.class);
        } catch (Exception e) {
            log.warn("Rejected unparseable webhook body", e);
            return java.util.Optional.empty();
        }

        java.util.Optional<String> ref = provider.extractReference(payload);
        if (ref.isEmpty()) {
            return java.util.Optional.empty(); // not a charge event we act on
        }
        return applyVerifiedPayment(ref.get(), payload.toString());
    }

    @Transactional
    public java.util.Optional<String> applyVerifiedPayment(String reference, String rawWebhook) {
        PaymentTransaction tx = paymentRepository.findByReference(reference).orElse(null);
        if (tx == null) {
            log.warn("Webhook for unknown reference {}", reference);
            return java.util.Optional.empty();
        }

        if (tx.getStatus() == PaymentStatus.SUCCESS) {
            log.info("Duplicate webhook for {} - already credited, ignoring", reference);
            return java.util.Optional.of(reference);
        }
        if (tx.getStatus() != PaymentStatus.PENDING) {
            log.warn("Webhook for non-pending transaction {} ({})", reference, tx.getStatus());
            return java.util.Optional.empty();
        }

        PaymentProvider.VerifyResult confirmed = activeProvider().verify(reference);
        if (!confirmed.paid()) {
            tx.setStatus(PaymentStatus.FAILED);
            tx.setRawWebhook(rawWebhook);
            paymentRepository.save(tx);
            log.warn("Provider re-query says {} is unpaid - marked FAILED", reference);
            return java.util.Optional.empty();
        }
        if (confirmed.amountKobo() != tx.getAmountKobo()) {
            tx.setStatus(PaymentStatus.FAILED);
            tx.setRawWebhook(rawWebhook);
            paymentRepository.save(tx);
            log.error("Amount mismatch on {}: expected {} kobo, provider says {}",
                    reference, tx.getAmountKobo(), confirmed.amountKobo());
            return java.util.Optional.empty();
        }

        creditService.purchaseCredits(tx.getUser().getId(), tx.getCredits(), reference);
        tx.setStatus(PaymentStatus.SUCCESS);
        tx.setPaidAt(LocalDateTime.now());
        tx.setRawWebhook(rawWebhook);
        paymentRepository.save(tx);

        log.info("Credited {} credits to user {} for payment {}",
                tx.getCredits(), tx.getUser().getId(), reference);
        return java.util.Optional.of(reference);
    }

    // ------------------------------------------------------------------
    // Browser callback reconciliation + history
    // ------------------------------------------------------------------

    /**
     * Reconcile after the browser returns from the provider page. Same checks as
     * the webhook (re-query, then apply) - a user refreshing this endpoint still
     * cannot mint credits without the provider confirming first.
     */
    @Transactional
    public PaymentTransaction verifyAndApply(String reference) {
        java.util.Optional<String> applied = applyVerifiedPayment(reference, "browser-callback");
        return paymentRepository.findByReference(reference)
                .orElseThrow(() -> new IllegalArgumentException("Unknown payment reference"));
    }

    @Transactional(readOnly = true)
    public java.util.List<PaymentTransaction> history(UUID userId) {
        return paymentRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /**
     * Enable/disable flag for the SPA (mirrors the Google providers pattern): false
     * while no secret key is configured, so the frontend hides the Buy button instead
     * of opening a checkout that is guaranteed to fail.
     */
    public boolean isConfigured() {
        try {
            activeProvider();
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private PaymentProvider activeProvider() {
        if ("mock".equalsIgnoreCase(configuredProvider)) {
            return providers.stream()
                    .filter(p -> p instanceof MockPaymentProvider)
                    .findFirst().orElseThrow();
        }
        return providers.stream()
                .filter(p -> p.getName().equalsIgnoreCase(configuredProvider))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown payment provider: " + configuredProvider));
    }
}
