package com.contentanalytics.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

/**
 * Redis-backed distributed lock with fencing tokens.
 *
 * Use {@link #withLock(String, Runnable)} to guard critical sections that
 * must execute on exactly one backend instance at a time (e.g. subscription
 * period rollover, credit-pack fulfillment, webhook processing).
 *
 * Lock acquisition uses {@code SET key value NX EX ttl} — atomic and safe
 * across instances. Release uses a compare-and-delete Lua script so only the
 * owner can unlock. Fencing versions bump on acquire/release so stale holders
 * can be detected by callers that check {@link #currentFencingVersion(String)}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DistributedLockService {

    private static final String LOCK_PREFIX = "lock:";
    private static final String VERSION_PREFIX = "lock:version:";

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
              return redis.call('del', KEYS[1])
            else
              return 0
            end
            """, Long.class);

    private final StringRedisTemplate redis;

    @Value("${app.distributed-lock.default-ttl-seconds:30}")
    private int defaultTtlSeconds;

    /**
     * Execute {@code action} while holding a distributed lock on {@code lockKey}.
     */
    public void withLock(String lockKey, Runnable action, int waitSeconds) {
        String fencingToken = acquireLock(lockKey, waitSeconds);
        try {
            action.run();
        } finally {
            releaseLock(lockKey, fencingToken);
        }
    }

    public void withLock(String lockKey, Runnable action) {
        withLock(lockKey, action, 5);
    }

    /**
     * Current fencing version for {@code lockKey}, or 0 if none.
     */
    public long currentFencingVersion(String lockKey) {
        String raw = redis.opsForValue().get(VERSION_PREFIX + lockKey);
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private String acquireLock(String lockKey, int waitSeconds) {
        String fullKey = LOCK_PREFIX + lockKey;
        String token = UUID.randomUUID().toString();
        long deadline = System.currentTimeMillis() + (long) waitSeconds * 1000;

        while (System.currentTimeMillis() < deadline) {
            Boolean acquired = redis.opsForValue().setIfAbsent(fullKey, token,
                    Duration.ofSeconds(defaultTtlSeconds));
            if (Boolean.TRUE.equals(acquired)) {
                redis.opsForValue().increment(VERSION_PREFIX + lockKey);
                return token;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LockAcquisitionException("Interrupted waiting for lock: " + lockKey, e);
            }
        }
        throw new LockAcquisitionException("Could not acquire distributed lock: " + lockKey);
    }

    private void releaseLock(String lockKey, String token) {
        String fullKey = LOCK_PREFIX + lockKey;
        Long deleted = redis.execute(RELEASE_SCRIPT, Collections.singletonList(fullKey), token);
        if (deleted != null && deleted > 0) {
            redis.opsForValue().increment(VERSION_PREFIX + lockKey);
        }
    }

    public static class LockAcquisitionException extends RuntimeException {
        public LockAcquisitionException(String message, Throwable cause) {
            super(message, cause);
        }

        public LockAcquisitionException(String message) {
            super(message);
        }
    }
}
