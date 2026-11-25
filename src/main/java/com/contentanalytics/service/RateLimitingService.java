package com.contentanalytics.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Redis-backed fixed-window rate limiter.
 *
 * The increment and the first-window expiry are applied in ONE Lua script.
 * The old INCR-then-EXPIRE pair had two failure modes: a crash between the two
 * calls left a key with no TTL, and two instances could both believe they were
 * the "first" caller. Rejected requests decrement the counter again so an
 * attacker cannot inflate it without bound.
 *
 * Redis is treated as best-effort infrastructure: if it is unavailable the
 * request is allowed and a warning is logged. Business-level protections
 * (DB locks, webhook HMAC, unique indexes) are the real safety boundary.
 */
@Service
@Slf4j
public class RateLimitingService {

    private static final String RATE_LIMIT_PREFIX = "ratelimit:";

    private static final DefaultRedisScript<Long> CHECK_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[2])
            end
            if current > tonumber(ARGV[1]) then
              -- Do not let rejected requests grow the counter forever.
              redis.call('DECR', KEYS[1])
            end
            return current
            """, Long.class);

    private final StringRedisTemplate redis;

    public RateLimitingService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Outcome of one rate-limit check. */
    public record RateLimitResult(boolean allowed, long remaining, long retryAfterSeconds) {
    }

    /**
     * Atomically consume one token from the bucket and report the outcome.
     */
    public RateLimitResult check(String key, int maxRequests, int windowSeconds) {
        String redisKey = RATE_LIMIT_PREFIX + key;
        try {
            Long current = redis.execute(CHECK_SCRIPT, List.of(redisKey),
                    String.valueOf(maxRequests), String.valueOf(windowSeconds));
            long used = current == null ? 0L : current;
            boolean allowed = used <= maxRequests;
            long remaining = Math.max(0L, (long) maxRequests - used);
            long retryAfter = allowed ? 0L : Math.max(1L, getRemainingTtl(key));
            if (!allowed) {
                log.debug("Rate limit exceeded for key {}", key);
            }
            return new RateLimitResult(allowed, remaining, retryAfter);
        } catch (RuntimeException e) {
            log.warn("Rate-limit store unavailable for key {} - allowing request: {}", key, e.getMessage());
            return new RateLimitResult(true, maxRequests, 0L);
        }
    }

    /**
     * Checks whether a request is allowed under the rate limit and, if so,
     * increments the counter atomically.
     */
    public boolean isAllowed(String key, int maxRequests, int windowSeconds) {
        return check(key, maxRequests, windowSeconds).allowed();
    }

    /** Returns the remaining TTL (seconds) for a rate-limit bucket, or -1 if none. */
    public long getRemainingTtl(String key) {
        String redisKey = RATE_LIMIT_PREFIX + key;
        try {
            Long ttl = redis.getExpire(redisKey);
            return ttl != null ? ttl : -1L;
        } catch (RuntimeException e) {
            log.warn("Could not read rate-limit TTL for key {}: {}", key, e.getMessage());
            return -1L;
        }
    }
}
