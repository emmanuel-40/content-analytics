package com.contentanalytics.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Redis-backed idempotency store for provider webhooks (Paystack, etc.).
 *
 * Webhooks are deduplicated by the provider's event id (e.g. Paystack's
 * {@code x-paystack-event} header or the {@code event} field in the JSON body).
 * Once an event id has been processed, subsequent deliveries of the same event
 * are ignored — preventing duplicate credit grants on retried webhooks.
 *
 * Keys use a 7-day TTL: Paystack retries failed webhooks for up to 24h, and
 * a week gives ample margin while preventing unbounded Redis growth.
 *
 * Security: callers must verify the provider HMAC before calling any
 * method here. Dedupe keys derived from unverified bodies let an attacker burn
 * the key for a legitimate event. {@code PaymentService} does this in that order
 * and releases the claim for every outcome that does not credit an account.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookIdempotencyService {

    private static final String WEBHOOK_PREFIX = "webhook:processed:";

    private static final Duration DEFAULT_TTL = Duration.ofDays(7);

    private final StringRedisTemplate redis;

    /**
     * Returns {@code true} if this event id has already been processed.
     * If not, marks it as processed and returns {@code false}.
     *
     * @param provider  provider name (e.g. "paystack") — namespaces the key
     * @param eventId   provider's immutable event id
     * @return {@code true} if already processed (skip), {@code false} if new (proceed)
     */
    public boolean isDuplicate(String provider, String eventId) {
        if (eventId == null || eventId.isBlank()) {
            // No event id — cannot deduplicate, proceed (safer than blocking)
            return false;
        }
        String key = WEBHOOK_PREFIX + provider + ":" + eventId;
        Boolean set = redis.opsForValue().setIfAbsent(key, "1", DEFAULT_TTL);
        return Boolean.FALSE.equals(set);
    }

    /**
     * Manually mark an event as processed (used after successful handling).
     */
    public void markProcessed(String provider, String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return;
        }
        String key = WEBHOOK_PREFIX + provider + ":" + eventId;
        redis.opsForValue().setIfAbsent(key, "1", DEFAULT_TTL);
        log.debug("Marked webhook event as processed: provider={} eventId={}", provider, eventId);
    }

    /**
     * Forget a claimed event id so the provider can retry after a failed handle.
     */
    public void clear(String provider, String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return;
        }
        String key = WEBHOOK_PREFIX + provider + ":" + eventId;
        redis.delete(key);
        log.debug("Cleared webhook idempotency key: provider={} eventId={}", provider, eventId);
    }
}
