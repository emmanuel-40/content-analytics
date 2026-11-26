package com.contentanalytics.service.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Paystack (https://paystack.com) - cards, bank transfer, USSD, mobile money.
 *
 * Test mode uses {@code sk_test_...} keys and the fake card
 * {@code 4084 0840 8408 4081}: the whole flow runs end to end with zero real
 * money moving. Going live is a key swap on the Render dashboard only
 * ({@code PAYSTACK_SECRET_KEY} / {@code PAYSTACK_PUBLIC_KEY}) - no code change.
 *
 * Security posture: the browser callback URL is UX only. Credits move only
 * after (1) HMAC verification of the webhook and (2) a server-side
 * {@code GET /transaction/verify} confirming {@code status=success} for the
 * exact amount. The frontend's word is never taken.
 */
@Service
@Slf4j
public class PaystackProvider implements PaymentProvider {

    private static final String BASE_URL = "https://api.paystack.co";

    @Value("${app.payments.paystack.secret-key:}")
    private String secretKey;

    @Value("${app.payments.paystack.public-key:}")
    private String publicKey;

    /** Explicit timeouts: a slow provider must never hold a DB connection hostage. */
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getName() {
        return "PAYSTACK";
    }

    @Override
    public InitializeResult initialize(String email, int amountCents, String reference, String callbackUrl) {
        requireConfigured();

        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "email", email,
                    "amount", amountCents,
                    "currency", "USD",
                    "reference", reference,
                    "callback_url", callbackUrl));

            Request request = new Request.Builder()
                    .url(BASE_URL + "/transaction/initialize")
                    .addHeader("Authorization", "Bearer " + secretKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(json, MediaType.parse("application/json")))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.error("Paystack initialize failed: {} - {}", response.code(), body);
                    String code = "";
                    try {
                        code = objectMapper.readTree(body).path("code").asText("");
                    } catch (IOException parseFailure) {
                        log.debug("Could not parse Paystack error body", parseFailure);
                    }
                    if ("unsupported_currency".equals(code)) {
                        // The integration is valid, but the merchant account has not
                        // been enabled for USD. This is actionable by the owner, so
                        // surface it instead of hiding it behind a generic 500.
                        throw new IllegalArgumentException(
                                "USD is not enabled on this Paystack account. Enable USD in Paystack settings, then retry.");
                    }
                    throw new IllegalStateException("Payment provider error (" + response.code() + ")");
                }
                JsonNode data = objectMapper.readTree(body).path("data");
                return new InitializeResult(
                        data.path("authorization_url").asText(),
                        data.path("access_code").asText());
            }
        } catch (IOException e) {
            log.error("Paystack initialize call failed", e);
            throw new IllegalStateException("Payment provider unreachable", e);
        }
    }
    @Override
    public VerifyResult verify(String reference) {
        requireConfigured();

        try {
            Request request = new Request.Builder()
                    .url(BASE_URL + "/transaction/verify/" + reference)
                    .addHeader("Authorization", "Bearer " + secretKey)
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                if (response.code() >= 500) {
                    // Provider-side failure: throw so the call is retried and the
                    // transaction stays PENDING. Never mark a paid charge FAILED
                    // because Paystack had a bad five minutes.
                    log.error("Paystack verify unavailable for {}: HTTP {}", reference, response.code());
                    throw new IllegalStateException("Payment provider unavailable (" + response.code() + ")");
                }
                if (!response.isSuccessful()) {
                    // 4xx means the reference itself was rejected/unknown.
                    log.warn("Paystack verify rejected reference {}: {}", reference, response.code());
                    return new VerifyResult(false, 0, null);
                }
                JsonNode data = objectMapper.readTree(body).path("data");
                // The checkout is opened in USD; refuse to credit a charge that
                // Paystack settled in another currency even if status says success.
                boolean currencyOk = data.hasNonNull("currency")
                        && "USD".equalsIgnoreCase(data.path("currency").asText());
                boolean paid = "success".equalsIgnoreCase(data.path("status").asText()) && currencyOk;
                return new VerifyResult(paid, data.path("amount").asInt(0),
                        data.path("paid_at").asText(null));
            }
        } catch (IOException e) {
            log.error("Paystack verify call failed for {}", reference, e);
            throw new IllegalStateException("Payment provider unreachable", e);
        }
    }

    @Override
    public boolean verifyWebhookSignature(byte[] rawBody, String signature) {
        if (secretKey == null || secretKey.isBlank() || signature == null || rawBody == null) {
            return false;
        }
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA512");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            byte[] digest = mac.doFinal(rawBody);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) { hex.append(String.format("%02x", b)); }
            return constantTimeEquals(hex.toString(), signature.trim().toLowerCase());
        } catch (Exception e) {
            log.error("Webhook HMAC computation failed", e);
            return false;
        }
    }

    @Override
    public java.util.Optional<String> extractReference(java.util.Map<String, Object> payload) {
        if (!"charge.success".equals(payload.get("event"))) { return java.util.Optional.empty(); }
        Object data = payload.get("data");
        if (data instanceof java.util.Map<?, ?> dataMap) {
            Object ref = dataMap.get("reference");
            if (ref != null && !ref.toString().isBlank()) { return java.util.Optional.of(ref.toString()); }
        }
        return java.util.Optional.empty();
    }

    /** Public key is safe to expose to the SPA (it only opens the popup). */
    public String getPublicKey() { return publicKey == null ? "" : publicKey; }

    /** False while no secret key is configured - the SPA hides Buy until then. */
    public boolean isConfigured() {
        return secretKey != null && !secretKey.isBlank();
    }

    /** Test keys start with {@code sk_test_}; live keys with {@code sk_live_}. */
    @Override
    public boolean isTestMode() {
        return secretKey != null && secretKey.startsWith("sk_test_");
    }

    private void requireConfigured() {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException(
                    "Paystack is not configured. Paste test keys into application.properties "
                    + "(app.payments.paystack.secret-key) or set PAYSTACK_SECRET_KEY on Render.");
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) { return false; }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) { diff |= a.charAt(i) ^ b.charAt(i); }
        return diff == 0;
    }
}

