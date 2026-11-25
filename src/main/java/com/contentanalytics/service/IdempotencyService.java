package com.contentanalytics.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Redis-backed idempotency store.
 *
 * Uses {@code SET key value NX EX ttl} for atomic check-and-set: only the first
 * request with a given idempotency key wins; duplicates get the cached response.
 * Keys expire automatically after a configurable TTL (default 24h).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    private static final String IDEMPOTENCY_PREFIX = "idem:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /**
     * Attempt to execute an idempotent operation.
     *
     * If a cached response exists for {@code key}, it is returned and {@code operation}
     * is not invoked. Otherwise {@code operation} is invoked, its result is
     * cached, and returned.
     *
     * @param key          idempotency key from the {@code Idempotency-Key} header
     * @param endpoint     logical endpoint name, used as a Redis key namespace
     * @param ttlHours     how long to cache the response
     * @param operation    the actual business logic; returns a {@code CachedResponse}
     * @param T          cached response type
     * @return the cached or freshly computed response
     */
    public <T> CachedResponse<T> execute(
            String key, String endpoint, long ttlHours,
            IdempotencyOperation<T> operation) {
        return execute(key, endpoint, null, ttlHours, operation);
    }

    /**
     * Same as {@link #execute(String, String, long, IdempotencyOperation)} but
     * namespaces the key with a caller-supplied scope (authenticated user id +
     * request-body fingerprint). Without a scope, two users sharing a client-
     * chosen key would share one cached response.
     */
    public <T> CachedResponse<T> execute(
            String key, String endpoint, String scope, long ttlHours,
            IdempotencyOperation<T> operation) {

        String redisKey = IDEMPOTENCY_PREFIX + endpoint
                + (scope == null || scope.isBlank() ? "" : ":" + scope)
                + ":" + key;

        // 1. Check cache first. Redis is an optimisation; a Redis outage must
        // not block checkout because unique DB references are the real guard.
        String cached = readCached(redisKey);
        if (cached != null && !cached.isBlank()) {
            try {
                CachedResponse<T> hit = objectMapper.readValue(cached,
                        objectMapper.getTypeFactory().constructParametricType(
                                CachedResponse.class, objectMapper.getTypeFactory().constructType(Object.class)));
                log.debug("Idempotency hit for key {} on endpoint {}", key, endpoint);
                return hit;
            } catch (Exception e) {
                log.warn("Failed to deserialize cached idempotency response for key {}: {}",
                        key, e.getMessage());
                // Fall through and re-execute
            }
        }

        // 2. Not cached — claim with SET NX so only one caller executes.
        Boolean set;
        try {
            set = redis.opsForValue().setIfAbsent(redisKey, "__PENDING__",
                    Duration.ofHours(ttlHours));
        } catch (RuntimeException e) {
            log.warn("Idempotency store unavailable for key {} - executing without a Redis claim: {}",
                    key, e.getMessage());
            set = Boolean.TRUE;
        }
        if (Boolean.FALSE.equals(set)) {
            // Another request owns this key; poll briefly for the cached response
            for (int i = 0; i < 20; i++) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                cached = readCached(redisKey);
                if (cached != null && !"__PENDING__".equals(cached) && !cached.isBlank()) {
                    try {
                        CachedResponse<T> hit = objectMapper.readValue(cached,
                                objectMapper.getTypeFactory().constructParametricType(
                                        CachedResponse.class,
                                        objectMapper.getTypeFactory().constructType(Object.class)));
                        log.debug("Idempotency hit after wait for key {} on endpoint {}", key, endpoint);
                        return hit;
                    } catch (Exception e) {
                        log.warn("Failed to deserialize pending idempotency response for key {}: {}",
                                key, e.getMessage());
                        break;
                    }
                }
            }
            // Still pending — fail closed rather than double-executing side effects
            log.warn("Concurrent idempotency request still pending for key {} on endpoint {}",
                    key, endpoint);
            throw new IllegalStateException(
                    "A request with this Idempotency-Key is already in progress; retry shortly");
        }

        // 3. Execute the operation. Release the claim on ANY failure so a
        // transient provider outage cannot lock the key for the full TTL.
        CachedResponse<T> result;
        try {
            result = operation.execute();
        } catch (RuntimeException e) {
            try {
                redis.delete(redisKey);
            } catch (RuntimeException cleanupFailure) {
                log.warn("Could not release idempotency claim {}: {}", redisKey,
                        cleanupFailure.getMessage());
            }
            throw e;
        }

        // 4. Cache the result (overwrite the __PENDING__ placeholder).
        // If caching fails we drop the claim rather than leaving a poisoned
        // __PENDING__ marker; the underlying business operations enforce their
        // own DB-level idempotency (unique payment/credit references).
        try {
            String json = objectMapper.writeValueAsString(result);
            redis.opsForValue().set(redisKey, json, Duration.ofHours(ttlHours));
        } catch (Exception e) {
            log.error("Failed to cache idempotency response for key {} - releasing claim: {}",
                    key, e.getMessage());
            try {
                redis.delete(redisKey);
            } catch (RuntimeException cleanupFailure) {
                log.warn("Could not release idempotency claim {}: {}", redisKey,
                        cleanupFailure.getMessage());
            }
        }

        return result;
    }

    private String readCached(String redisKey) {
        try {
            return redis.opsForValue().get(redisKey);
        } catch (RuntimeException e) {
            log.warn("Could not read idempotency cache {}: {}", redisKey, e.getMessage());
            return null;
        }
    }

    /**
     * Result of an idempotent operation, serializable to JSON for Redis caching.
     *
     * @param T the response body type
     */
    public record CachedResponse<T>(
            int status,
            Map<String, String> headers,
            T body
    ) {
        public CachedResponse {
            if (headers == null) {
                headers = new HashMap<>();
            }
        }
    }

    /**
     * Callback executed when no cached response exists for the idempotency key.
     *
     * @param T the response body type
     */
    @FunctionalInterface
    public interface IdempotencyOperation<T> {
        CachedResponse<T> execute();
    }
}
