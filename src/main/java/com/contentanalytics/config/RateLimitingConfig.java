package com.contentanalytics.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Bucket4j;
import io.github.bucket4j.Refill;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
@Component
@Slf4j
public class RateLimitingConfig {

    private final ConcurrentHashMap<String, Bucket> cacheBuckets = new ConcurrentHashMap<>();

    /**
     * Hard cap on tracked clients. An unbounded map keyed by client IP is a memory-exhaustion
     * vector: an attacker rotating source addresses would grow it without limit.
     */
    @Value("${app.rate-limit.max-tracked-clients:10000}")
    private int maxTrackedClients;

    // Get rate limit bucket for an IP address 5 requests per minute per IP

    public Bucket getLoginBucket(String clientIp) {
        return bucketFor("login:" + clientIp, this::createLoginBucket);
    }

    /**
     * Get rate limit bucket for registration
     * 2 requests per hour per IP
     */
    public Bucket getRegisterBucket(String clientIp) {
        return bucketFor("register:" + clientIp, this::createRegisterBucket);
    }

    /**
     * Resolve a bucket, evicting the oldest entry when the tracked-client cap is reached.
     */
    private Bucket bucketFor(String key, java.util.function.Supplier<Bucket> factory) {
        Bucket existing = cacheBuckets.get(key);
        if (existing != null) {
            return existing;
        }

        if (cacheBuckets.size() >= maxTrackedClients) {
            // Evict an arbitrary (but safe) entry to bound memory usage.
            String victim = cacheBuckets.keySet().iterator().next();
            cacheBuckets.remove(victim);
            log.debug("Rate limit bucket cache at capacity ({}), evicted {}", maxTrackedClients, victim);
        }

        return cacheBuckets.computeIfAbsent(key, k -> factory.get());
    }

    private Bucket createLoginBucket() {
        Bandwidth limit = Bandwidth.classic(5, Refill.intervally(5, Duration.ofMinutes(1)));
        return Bucket4j.builder()
                .addLimit(limit)
                .build();
    }

    private Bucket createRegisterBucket() {
        Bandwidth limit = Bandwidth.classic(4, Refill.intervally(4, Duration.ofHours(1)));
        return Bucket4j.builder()
                .addLimit(limit)
                .build();
    }

}
