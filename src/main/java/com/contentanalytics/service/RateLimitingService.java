package com.contentanalytics.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Redis-backed sliding-window rate limiter.
 *
 * Uses atomic Redis INCR + EXPIRE so the counter is consistent across all
 * backend instances. No two requests can both sneak through a boundary.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RateLimitingService {

    private static final String RATE_LIMIT_PREFIX = "ratelimit:";

    private final StringRedisTemplate redis;

    /**
     * Checks whether a request is allowed under the rate limit and, if so,
     * increments the counter atomically.
     *
     * @param key            unique bucket key (e.g. endpoint + IP or user id)
     * @param maxRequests    max requests allowed in the window
     * @param windowSeconds  window length in seconds
     * @return {@code true} if the request is allowed, {@code false} if over limit
     */
    public boolean isAllowed(String key, int maxRequests, int windowSeconds) {
        String redisKey = RATE_LIMIT_PREFIX + key;

        // Atomic increment
        Long count = redis.opsForValue().increment(redisKey);

        // Set expiry only on the first request of the window
        if (count != null && count == 1) {
            redis.expire(redisKey, Duration.ofSeconds(windowSeconds));
        }

        boolean allowed = count != null && count <= maxRequests;

        if (!allowed) {
            log.debug("Rate limit exceeded for key {}", key);
        }
        return allowed;
    }

    /**
     * Returns the remaining TTL (seconds) for a rate-limit bucket, or -1 if none.
     */
    public long getRemainingTtl(String key) {
        String redisKey = RATE_LIMIT_PREFIX + key;
        Long ttl = redis.getExpire(redisKey);
        return ttl != null ? ttl : -1;
    }
}
