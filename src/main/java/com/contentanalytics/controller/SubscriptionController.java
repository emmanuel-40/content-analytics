package com.contentanalytics.controller;

import com.contentanalytics.dto.ApiResponse;
import com.contentanalytics.dto.CreditTransactionDto;
import com.contentanalytics.dto.PurchaseCreditsRequestDto;
import com.contentanalytics.dto.SubscriptionDto;
import com.contentanalytics.entity.SubscriptionTier;
import com.contentanalytics.service.CreditService;
import com.contentanalytics.util.SecurityUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Subscription and credit endpoints for the freemium model.
 */
@RestController
@RequestMapping("/api/subscription")
@RequiredArgsConstructor
@Slf4j
public class SubscriptionController {

    private final CreditService creditService;
    private final SecurityUtil securityUtil;

    /**
     * GET /api/subscription
     * Current tier, allowance and remaining credits.
     */
    @GetMapping
    public ResponseEntity<SubscriptionDto> getSubscription() {
        UUID userId = securityUtil.getCurrentUserId();
        return ResponseEntity.ok(creditService.getSubscription(userId));
    }

    /**
     * GET /api/subscription/tiers
     * Public catalogue of available tiers and their allowances.
     */
    @GetMapping("/tiers")
    public ResponseEntity<List<Map<String, Object>>> getTiers() {
        List<Map<String, Object>> tiers = Arrays.stream(SubscriptionTier.values())
                .map(tier -> Map.<String, Object>of(
                        "tier", tier.name(),
                        "label", tier.getLabel(),
                        "monthlyCredits", tier.getMonthlyCredits()))
                .toList();

        return ResponseEntity.ok(tiers);
    }

    /**
     * GET /api/subscription/transactions
     * Credit ledger, newest first.
     */
    @GetMapping("/transactions")
    public ResponseEntity<List<CreditTransactionDto>> getTransactions(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {

        UUID userId = securityUtil.getCurrentUserId();
        return ResponseEntity.ok(creditService.getTransactionHistory(userId, page, size));
    }

    /**
     * POST /api/subscription/purchase-credits
     * Buy additional credits (pay-as-you-go). Purchased credits survive the monthly reset.
     * <p>
     * ADMIN/TEST ONLY. This endpoint grants credits without money moving and
     * exists so integration tests and local demos can seed balances. Real
     * purchases flow through {@code POST /api/payments/initialize} and are
     * credited by the verified Paystack webhook - never by this endpoint.
     */
    @PostMapping("/purchase-credits")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse> purchaseCredits(
            @Valid @RequestBody PurchaseCreditsRequestDto request) {

        UUID userId = securityUtil.getCurrentUserId();
        log.info("Credit purchase request from user: {} for {} credit(s)", userId, request.getCredits());

        SubscriptionDto updated = creditService.purchaseCredits(
                userId, request.getCredits(), request.getReferenceId());

        return ResponseEntity.ok(ApiResponse.builder()
                .status(HttpStatus.OK.value())
                .message("Purchased " + request.getCredits() + " credit(s)")
                .data(updated)
                .build());
    }

    /**
     * PUT /api/subscription/tier
     * Change tier. Admin-only.
     */
    @PutMapping("/tier")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SubscriptionDto> changeTier(@RequestParam("tier") String tier) {

        SubscriptionTier newTier;
        try {
            newTier = SubscriptionTier.valueOf(tier.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid tier '" + tier + "'. Allowed: "
                    + Arrays.toString(SubscriptionTier.values()));
        }

        UUID userId = securityUtil.getCurrentUserId();
        return ResponseEntity.ok(creditService.changeTier(userId, newTier));
    }

}