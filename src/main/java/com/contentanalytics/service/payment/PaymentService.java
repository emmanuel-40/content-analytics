package com.contentanalytics.service.payment;

import com.contentanalytics.entity.*;
import com.contentanalytics.repository.PaymentTransactionRepository;
import com.contentanalytics.repository.UserRepository;
import com.contentanalytics.service.CreditService;
import com.contentanalytics.service.WebhookIdempotencyService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Checkout orchestration: initialize, webhook, verify, history.
 *
 * Invariants:
 *
 *   The PENDING payment row is committed before calling the provider,
 * so a webhook that beats our HTTP response can still find it.
 * Webhook dedupe is claimed only after HMAC verification, and is
 * released for every outcome that does not credit an account.
 * Credits move only inside {@link #applyVerifiedPayment}, after a
 * server-side re-query and an exact amount check. The browser callback
 * never mints credits on its own.
 * */
@Service
@Slf4j
public class PaymentService {

    /** Charge currency sent to Paystack and reported to the SPA. */
    public static final String CURRENCY = "USD";

    /** Price list: credits - USD cents. Single place so API, webhook and tests agree. */
    public static final Map<Integer, Integer> CREDIT_PACKS = Map.of(
            10, 200,    // 10 credits = USD 2.00 (Paystack USD minimum)
            50, 800,    // 50 credits = USD 8.00 (save 20%)
            120, 1600); // 120 credits = USD 16.00 (save 33%)

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> WEBHOOK_PAYLOAD_TYPE =
            new TypeReference<Map<String, Object>>() { };

    private final PaymentTransactionRepository paymentRepository;
    private final UserRepository userRepository;
    private final CreditService creditService;
    private final PaystackProvider paystack;
    private final WebhookIdempotencyService webhookIdempotency;
    private final TransactionTemplate transactionTemplate;

    @Value("${app.payments.callback-url:http://localhost:5173/payment/callback}")
    private String callbackUrl;

    public PaymentService(PaymentTransactionRepository paymentRepository,
                          UserRepository userRepository,
                          CreditService creditService,
                          PaystackProvider paystack,
                          WebhookIdempotencyService webhookIdempotency,
                          PlatformTransactionManager transactionManager) {
        this.paymentRepository = paymentRepository;
        this.userRepository = userRepository;
        this.creditService = creditService;
        this.paystack = paystack;
        this.webhookIdempotency = webhookIdempotency;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ------------------------------------------------------------------
    // Initialize
    // ------------------------------------------------------------------

    /**
     * Start a checkout: validate the pack, commit a PENDING row, then ask
     * the provider for a checkout URL outside any transaction.
     */
    public PaymentTransaction initializeCheckout(UUID userId, int credits) {
        Integer amountCents = CREDIT_PACKS.get(credits);
        if (amountCents == null) {
            throw new IllegalArgumentException(
                    "Unknown credit pack '" + credits + "'. Available: " + CREDIT_PACKS.keySet());
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        String reference = "ca-" + UUID.randomUUID();
        PaymentProvider provider = paystack;

        PaymentTransaction tx = PaymentTransaction.builder()
                .user(user)
                .reference(reference)
                .provider(provider.getName())
                .amountCents(amountCents)
                .credits(credits)
                .status(PaymentStatus.PENDING)
                .build();

        // 1. Durably persist the PENDING row BEFORE the provider knows the reference.
        PaymentTransaction pending = transactionTemplate.execute(status ->
                paymentRepository.saveAndFlush(tx));
        if (pending == null) {
            throw new IllegalStateException("Failed to persist pending payment");
        }

        // 2. External HTTP call with no DB connection or row lock held.
        PaymentProvider.InitializeResult checkout =
                provider.initialize(user.getEmail(), amountCents, reference, callbackUrl);

        // 3. Short transaction to store the resulting checkout URL.
        PaymentTransaction result = transactionTemplate.execute(status -> {
            PaymentTransaction stored = paymentRepository.findById(pending.getId())
                    .orElseThrow(() -> new IllegalStateException("Payment row disappeared"));
            stored.setCheckoutUrl(checkout.checkoutUrl());
            return paymentRepository.save(stored);
        });

        log.info("Checkout {} opened for user {} ({} credits, {} USD cents)",
                reference, userId, credits, amountCents);
        return result;
    }

    // ------------------------------------------------------------------
    // Webhook
    // ------------------------------------------------------------------

    /** String overload kept for callers/tests that already hold a decoded body. */
    public Optional<String> handleWebhook(String rawBody, String signature) {
        return handleWebhook(rawBody == null ? new byte[0]
                : rawBody.getBytes(StandardCharsets.UTF_8), signature);
    }

    /**
     * Apply a provider webhook. Safe to call repeatedly and safe to call with
     * forged bodies: HMAC is checked first, the dedupe claim is taken second,
     * and the claim is released for every non-crediting outcome.
     *
     * @param rawBody exact request bytes (HMAC covers these, not re-serialized JSON)
     * @return the stored reference, or empty when the payload was ignored
     */
    public Optional<String> handleWebhook(byte[] rawBody, String signature) {
        PaymentProvider provider = paystack;

        // HMAC FIRST. Nothing derived from the body is trusted before this line.
        if (!provider.verifyWebhookSignature(rawBody, signature)) {
            log.warn("Rejected webhook with bad signature (provider {})", provider.getName());
            return Optional.empty();
        }

        Map<String, Object> payload;
        try {
            payload = MAPPER.readValue(rawBody, WEBHOOK_PAYLOAD_TYPE);
        } catch (Exception e) {
            log.warn("Rejected unparseable webhook body", e);
            return Optional.empty();
        }

        Optional<String> ref = provider.extractReference(payload);
        if (ref.isEmpty()) {
            return Optional.empty(); // not a charge event we act on
        }

        String reference = ref.get();
        if (!claimWebhook(reference)) {
            log.info("Duplicate webhook delivery for {} - acknowledging without re-applying", reference);
            return Optional.of(reference);
        }

        try {
            Optional<String> applied = applyVerifiedPayment(reference, new String(rawBody, StandardCharsets.UTF_8));
            if (applied.isPresent()) {
                // applyVerifiedPayment commits its own transaction before returning.
                // Burn the dedupe key only after that commit succeeds.
                markWebhookProcessed(reference);
            } else {
                // Unknown reference (e.g. webhook beat the PENDING commit) or
                // definitively unpaid: release the claim so a retry can succeed.
                releaseWebhook(reference);
            }
            return applied;
        } catch (RuntimeException e) {
            releaseWebhook(reference);
            throw e;
        }
    }

    /** Best-effort Redis claim; the DB status guard remains the source of truth. */
    private boolean claimWebhook(String reference) {
        try {
            return !webhookIdempotency.isDuplicate("paystack", reference);
        } catch (RuntimeException e) {
            log.warn("Webhook dedupe store unavailable - relying on the DB status guard: {}", e.getMessage());
            return true;
        }
    }

    private void markWebhookProcessed(String reference) {
        try {
            webhookIdempotency.markProcessed("paystack", reference);
        } catch (RuntimeException e) {
            log.warn("Could not mark webhook processed (DB guard still applies): {}", e.getMessage());
        }
    }

    private void releaseWebhook(String reference) {
        try {
            webhookIdempotency.clear("paystack", reference);
        } catch (RuntimeException e) {
            log.warn("Could not release webhook claim: {}", e.getMessage());
        }
    }

    /**
     * Re-query the provider, then credit exactly once.
     *
     * The provider HTTP call is deliberately OUTSIDE the transaction: a slow
     * Paystack response must not hold a DB connection or a pessimistic row lock.
     * Settlement still serialises through a short REQUIRES_NEW transaction and
     * the SUCCESS status guard, so concurrent webhook + browser-verify calls
     * cannot credit twice.
     */
    public Optional<String> applyVerifiedPayment(String reference, String rawWebhook) {
        // 5xx/unreachable throws here; the payment stays PENDING and the caller
        // releases the webhook claim so Paystack's retry can try again.
        PaymentProvider.VerifyResult confirmed = paystack.verify(reference);

        Optional<String> result = transactionTemplate.execute(status ->
                applyConfirmedPayment(reference, confirmed, rawWebhook));
        return result == null ? Optional.empty() : result;
    }

    /** Runs inside {@link #transactionTemplate} with the payment row locked. */
    private Optional<String> applyConfirmedPayment(String reference,
                                                   PaymentProvider.VerifyResult confirmed,
                                                   String rawWebhook) {
        PaymentTransaction tx = paymentRepository.findByReferenceForUpdate(reference).orElse(null);
        if (tx == null) {
            log.warn("Webhook for unknown reference {} - leaving claim open for retry", reference);
            return Optional.empty();
        }

        if (tx.getStatus() == PaymentStatus.SUCCESS) {
            log.info("Duplicate delivery for {} - already credited, ignoring", reference);
            return Optional.of(reference);
        }
        if (tx.getStatus() != PaymentStatus.PENDING) {
            log.warn("Webhook for non-pending transaction {} ({})", reference, tx.getStatus());
            return Optional.empty();
        }

        if (!confirmed.paid()) {
            // A 4xx/verify miss is often provider propagation lag, not proof the
            // customer will never pay. Keep the row PENDING and let a later
            // webhook/browser retry reconcile it. Only an explicit amount
            // mismatch below is treated as definitively bad.
            tx.setRawWebhook(rawWebhook);
            paymentRepository.save(tx);
            log.warn("Provider re-query says {} is unpaid - keeping PENDING for retry", reference);
            return Optional.empty();
        }
        if (confirmed.amountCents() != tx.getAmountCents()) {
            tx.setStatus(PaymentStatus.FAILED);
            tx.setRawWebhook(rawWebhook);
            paymentRepository.save(tx);
            log.error("Amount mismatch on {}: expected {} USD cents, provider says {}",
                    reference, tx.getAmountCents(), confirmed.amountCents());
            return Optional.empty();
        }

        // Idempotent at the ledger: same reference never grants credits twice.
        creditService.purchaseCredits(tx.getUser().getId(), tx.getCredits(), reference);
        tx.setStatus(PaymentStatus.SUCCESS);
        tx.setPaidAt(LocalDateTime.now());
        tx.setRawWebhook(rawWebhook);
        paymentRepository.save(tx);

        log.info("Credited {} credits to user {} for payment {}",
                tx.getCredits(), tx.getUser().getId(), reference);
        return Optional.of(reference);
    }

    // ------------------------------------------------------------------
    // Browser callback reconciliation + history
    // ------------------------------------------------------------------

    public PaymentTransaction verifyAndApply(String reference) {
        return verifyAndApply(reference, null);
    }

    /**
     * Reconcile after the browser returns from the provider page. The optional
     * currentUserId is enforced by the controller so one user cannot inspect
     * another user's checkout details by guessing a reference.
     */
    public PaymentTransaction verifyAndApply(String reference, UUID currentUserId) {
        PaymentTransaction tx = paymentRepository.findByReference(reference)
                .orElseThrow(() -> new IllegalArgumentException("Unknown payment reference"));
        if (currentUserId != null && !tx.getUser().getId().equals(currentUserId)) {
            throw new IllegalArgumentException("Payment reference does not belong to the current user");
        }
        applyVerifiedPayment(reference, "browser-callback");
        return paymentRepository.findByReference(reference)
                .orElseThrow(() -> new IllegalArgumentException("Unknown payment reference"));
    }

    @Transactional(readOnly = true)
    public java.util.List<PaymentTransaction> history(UUID userId) {
        return paymentRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public boolean isConfigured() {
        return paystack.isConfigured();
    }

    /** Exposed to the SPA so sandbox checkouts can say "no real money moves". */
    public boolean isTestMode() {
        return paystack.isTestMode();
    }
}
