package com.contentanalytics.controller;

import com.contentanalytics.dto.InitializePaymentRequestDto;
import com.contentanalytics.dto.InitializePaymentResponseDto;
import com.contentanalytics.dto.PaymentTransactionDto;
import com.contentanalytics.entity.PaymentStatus;
import com.contentanalytics.entity.PaymentTransaction;
import com.contentanalytics.service.payment.PaymentService;
import com.contentanalytics.service.payment.PaystackProvider;
import com.contentanalytics.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Real-money checkout (Paystack, test mode until live keys land).
 * <p>
 * The money flow never trusts the browser: {@code /initialize} creates a
 * PENDING row and returns a checkout URL; {@code /webhook} (Paystack -&gt; us,
 * HMAC-verified, then server-side re-queried) is the only path that flips a
 * row to SUCCESS and grants credits; {@code /verify} lets the returning
 * browser reconcile through the same verified path.
 */
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Slf4j
public class PaymentController {

    private final PaymentService paymentService;
    private final PaystackProvider paystackProvider;
    private final SecurityUtil securityUtil;

    /**
     * POST /api/payments/initialize - open a checkout for a fixed credit pack.
     */
    @PostMapping("/initialize")
    public ResponseEntity<InitializePaymentResponseDto> initialize(
            @Valid @RequestBody InitializePaymentRequestDto request) {
        UUID userId = securityUtil.getCurrentUserId();

        PaymentTransaction tx = paymentService.initializeCheckout(userId, request.getCredits());

        return ResponseEntity.ok(InitializePaymentResponseDto.builder()
                .reference(tx.getReference())
                .checkoutUrl(tx.getCheckoutUrl())
                .accessCode("")
                .amountKobo(tx.getAmountKobo())
                .credits(tx.getCredits())
                .status(tx.getStatus().name())
                .publicKey(paystackProvider.getPublicKey())
                .build());
    }

    /**
     * POST /api/payments/webhook - Paystack event pipe. PUBLIC (Paystack cannot
     * log in), protected instead by HMAC over the raw body. Always 200 unless
     * the body is unreadable - Paystack retries non-2xx, and we must not turn a
     * duplicate delivery into an error storm.
     */
    @PostMapping("/webhook")
    public ResponseEntity<Map<String, String>> webhook(
            HttpServletRequest request,
            @RequestBody(required = false) String rawBody,
            @RequestHeader(value = "x-paystack-signature", required = false) String signature) {
        if (rawBody == null) {
            rawBody = "";
        }
        paymentService.handleWebhook(rawBody, signature);
        return ResponseEntity.ok(Map.of("status", "received"));
    }

    /**
     * GET /api/payments/verify?reference=... - reconcile after the browser
     * returns from Paystack. Runs the same verify-then-apply as the webhook.
     */
    @GetMapping("/verify")
    public ResponseEntity<PaymentTransactionDto> verify(@RequestParam("reference") String reference) {
        PaymentTransaction tx = paymentService.verifyAndApply(reference);
        return ResponseEntity.ok(toDto(tx));
    }

    /**
     * GET /api/payments/history - own checkout attempts, newest first.
     */
    @GetMapping("/history")
    public ResponseEntity<List<PaymentTransactionDto>> history() {
        UUID userId = securityUtil.getCurrentUserId();
        return ResponseEntity.ok(paymentService.history(userId).stream().map(this::toDto).toList());
    }

    /**
     * GET /api/payments/status - capability flag for the SPA (mirrors the
     * Google providers pattern): hide the Buy button while unconfigured.
     * Public: the pricing page needs it before login.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
                "configured", paymentService.isConfigured(),
                "packs", PaymentService.CREDIT_PACKS,
                "currency", "NGN"));
    }

    private PaymentTransactionDto toDto(PaymentTransaction tx) {
        return PaymentTransactionDto.builder()
                .id(tx.getId())
                .reference(tx.getReference())
                .provider(tx.getProvider())
                .amountKobo(tx.getAmountKobo())
                .credits(tx.getCredits())
                .status(tx.getStatus().name())
                .checkoutUrl(tx.getCheckoutUrl())
                .paidAt(tx.getPaidAt())
                .createdAt(tx.getCreatedAt())
                .build();
    }
}
