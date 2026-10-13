package com.contentanalytics.service.payment;

import com.contentanalytics.entity.*;
import com.contentanalytics.repository.CreditTransactionRepository;
import com.contentanalytics.repository.PaymentTransactionRepository;
import com.contentanalytics.repository.SubscriptionRepository;
import com.contentanalytics.repository.UserRepository;
import com.contentanalytics.service.CreditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Money-safety suite: the rules that stop free credits, double credits, and
 * forged webhooks. A stub provider stands in for Paystack (no network), so
 * these assert OUR logic: idempotency, amount matching, signature gating.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Payments (Paystack flow, stubbed provider)")
class PaymentServiceTest {

    private static final String MARKER = "pay-test";

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentTransactionRepository paymentRepository;

    @Autowired
    private CreditTransactionRepository creditTransactionRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CreditService creditService;

    private StubProvider stub;

    @BeforeEach
    void setUp() {
        stub = new StubProvider();
        ReflectionTestUtils.setField(paymentService, "paystack", stub);

        // Cleanup order matters (FK): payments and ledger rows first, then
        // subscriptions, then users. References are random UUIDs so cleanup is
        // scoped by joining back to our MARKER users, not by reference text.
        var ours = userRepository.findAll().stream()
                .filter(u -> u.getEmail() != null && u.getEmail().contains(MARKER))
                .map(com.contentanalytics.entity.User::getId)
                .collect(java.util.stream.Collectors.toSet());
        paymentRepository.findAll().stream()
                .filter(t -> t.getUser() != null && ours.contains(t.getUser().getId()))
                .forEach(paymentRepository::delete);
        creditTransactionRepository.findAll().stream()
                .filter(t -> t.getUser() != null && ours.contains(t.getUser().getId()))
                .forEach(creditTransactionRepository::delete);
        subscriptionRepository.findAll().stream()
                .filter(s -> s.getUser() != null && ours.contains(s.getUser().getId()))
                .forEach(subscriptionRepository::delete);
        userRepository.findAll().stream()
                .filter(u -> ours.contains(u.getId()))
                .forEach(userRepository::delete);
    }

    // ------------------------------------------------------------------
    // Initialize
    // ------------------------------------------------------------------

    @Test
    @DisplayName("initialize creates a PENDING row before any money moves")
    void initializeCreatesPendingRow() {
        User user = user(MARKER + "-init@example.com");

        PaymentTransaction tx = seedPending(user, 50);

        assertThat(tx.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(tx.getAmountKobo()).isEqualTo(400_000);
        assertThat(tx.getCredits()).isEqualTo(50);
        assertThat(tx.getCheckoutUrl()).contains("paystack.test");
    }

    @Test
    @DisplayName("unknown credit pack is rejected - clients cannot invent prices")
    void unknownPackIsRejected() {
        User user = user(MARKER + "-pack@example.com");

        assertThatThrownBy(() -> paymentService.initializeCheckout(user.getId(), 999))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown credit pack");
    }
    // ------------------------------------------------------------------
    // Webhook: the only path that grants credits
    // ------------------------------------------------------------------

    @Test
    @DisplayName("verified webhook credits the account exactly once, even delivered twice")
    void verifiedWebhookIsIdempotent() {
        User user = user(MARKER + "-idem@example.com");
        PaymentTransaction tx = seedPending(user, 10);
        int before = balance(user);

        stub.paid = true;
        stub.paidAmountKobo = 100_000;

        Optional<String> first = paymentService.handleWebhook(
                webhookBody(tx.getReference()), "stub-signature");
        Optional<String> second = paymentService.handleWebhook(
                webhookBody(tx.getReference()), "stub-signature");

        assertThat(first).hasValue(tx.getReference());
        assertThat(second).hasValue(tx.getReference());
        assertThat(balance(user)).isEqualTo(before + 10);
        assertThat(paymentRepository.findByReference(tx.getReference()))
                .hasValueSatisfying(t -> assertThat(t.getStatus()).isEqualTo(PaymentStatus.SUCCESS));
    }

    @Test
    @DisplayName("webhook with a bad signature grants nothing")
    void badSignatureGrantsNothing() {
        User user = user(MARKER + "-forged@example.com");
        PaymentTransaction tx = seedPending(user, 10);
        int before = balance(user);
        stub.paid = true;
        stub.paidAmountKobo = 100_000;

        Optional<String> applied = paymentService.handleWebhook(
                webhookBody(tx.getReference()), "attacker-signature");

        assertThat(applied).isEmpty();
        assertThat(balance(user)).isEqualTo(before);
        assertThat(paymentRepository.findByReference(tx.getReference()))
                .hasValueSatisfying(t -> assertThat(t.getStatus()).isEqualTo(PaymentStatus.PENDING));
    }

    @Test
    @DisplayName("amount mismatch fails the transaction instead of crediting")
    void amountMismatchFails() {
        User user = user(MARKER + "-tamper@example.com");
        PaymentTransaction tx = seedPending(user, 10);
        stub.paid = true;
        stub.paidAmountKobo = 1; // attacker edited the amount somewhere

        paymentService.handleWebhook(webhookBody(tx.getReference()), "stub-signature");

        assertThat(paymentRepository.findByReference(tx.getReference()))
                .hasValueSatisfying(t -> assertThat(t.getStatus()).isEqualTo(PaymentStatus.FAILED));
        assertThat(ledgerCount(user, CreditTransactionType.PURCHASE)).isZero();
    }

    @Test
    @DisplayName("unpaid re-query marks the transaction FAILED")
    void unpaidRequeryFails() {
        User user = user(MARKER + "-unpaid@example.com");
        PaymentTransaction tx = seedPending(user, 10);
        stub.paid = false;

        paymentService.handleWebhook(webhookBody(tx.getReference()), "stub-signature");

        assertThat(paymentRepository.findByReference(tx.getReference()))
                .hasValueSatisfying(t -> assertThat(t.getStatus()).isEqualTo(PaymentStatus.FAILED));
    }

    @Test
    @DisplayName("webhook for an unknown reference is ignored")
    void unknownReferenceIgnored() {
        stub.paid = true;
        stub.paidAmountKobo = 100_000;

        Optional<String> applied = paymentService.handleWebhook(
                webhookBody("ca-" + MARKER + "-nope"), "stub-signature");

        assertThat(applied).isEmpty();
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private User user(String email) {
        String base = email.substring(0, email.indexOf("@"));
        return userRepository.save(User.builder()
                .username(base)
.email(email)
.password("$2a$12$abcdefghijklmnopqrstuvABCDEFGHIJKLMNOPQRSTUV1234567890ab")
.role(UserRole.USER)
.status(UserStatus.ACTIVE)
.tokenVersion(1)
.emailVerified(true)
.provider(AuthProvider.LOCAL)
.createdAt(LocalDateTime.now())
.updatedAt(LocalDateTime.now())
.build());
    }

    private PaymentTransaction seedPending(User user, int credits) {
        stub.nextCheckoutUrl = "https://paystack.test/pay/" + MARKER;
        return paymentService.initializeCheckout(user.getId(), credits);
    }

    private String webhookBody(String reference) {
        return "{\"event\":\"charge.success\",\"data\":{\"reference\":\"" + reference + "\"}}";
    }

    private int balance(User user) {
        return creditService.getSubscription(user.getId()).getAvailableCredits();
    }

    private long ledgerCount(User user, CreditTransactionType type) {
        return creditTransactionRepository.findByUserIdAndType(user.getId(), type).size();
    }

    /** Stub Paystack: no network, behavior scripted per test via fields. */
    static class StubProvider extends PaystackProvider {
        boolean paid;
        int paidAmountKobo;
        String nextCheckoutUrl = "https://paystack.test/pay/x";

        public String getName() { return "STUB"; }

        public InitializeResult initialize(String email, int amountKobo, String reference, String callbackUrl) {
            return new InitializeResult(nextCheckoutUrl, "stub-access");
        }

        public VerifyResult verify(String reference) {
            return new VerifyResult(paid, paidAmountKobo, "2026-01-01T00:00:00Z");
        }

        public boolean verifyWebhookSignature(String rawBody, String signature) {
            return "stub-signature".equals(signature);
        }

        public java.util.Optional<String> extractReference(java.util.Map<String, Object> payload) {
            if (!"charge.success".equals(payload.get("event"))) {
                return java.util.Optional.empty();
            }
            Object data = payload.get("data");
            if (data instanceof java.util.Map<?, ?> dataMap) {
                Object ref = ((java.util.Map<String, Object>) dataMap).get("reference");
                if (ref != null && !ref.toString().isBlank()) {
                    return java.util.Optional.of(ref.toString());
                }
            }
            return java.util.Optional.empty();
        }
    }
}
