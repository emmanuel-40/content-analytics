package com.contentanalytics.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Redis-backed failed-login tracker with hard account lockout.
 *
 * Every failed password attempt increments a per-username counter in Redis.
 * When the counter reaches {@code maxAttempts}, the account is locked for
 * {@code lockoutMinutes} by writing a separate lock key. A successful login
 * resets the counter and removes the lock.
 *
 * All counters are keyed by username (not IP) so a locked account stays
 * locked even if the attacker rotates source addresses. Counters and locks both
 * expire automatically so a temporary password spray cannot permanently disable
 * an account.
 *
 * Redis is best-effort here: an outage fails open so the real credential check
 * in the database still decides whether a login succeeds.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoginAttemptService {

    private static final String ATTEMPTS_PREFIX = "login:attempts:";
    private static final String LOCK_PREFIX = "login:lock:";

    private final StringRedisTemplate redis;

    @Value("${app.lockout.max-attempts:5}")
    private int maxAttempts;

    @Value("${app.lockout.lockout-minutes:30}")
    private int lockoutMinutes;

    @Value("${app.lockout.reset-minutes:60}")
    private int resetMinutes;

    /**
     * Returns {@code true} if the account is currently locked out.
     */
    public boolean isLocked(String username) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(LOCK_PREFIX + username));
        } catch (DataAccessException e) {
            log.warn("Login lockout store unavailable - allowing attempt for {}: {}", username, e.getMessage());
            return false;
        }
    }

    /**
     * Returns the remaining lockout time in seconds, or -1 if not locked.
     */
    public long getLockoutRemainingSeconds(String username) {
        try {
            Long ttl = redis.getExpire(LOCK_PREFIX + username);
            return ttl != null ? ttl : -1;
        } catch (DataAccessException e) {
            log.warn("Login lockout TTL unavailable for {}: {}", username, e.getMessage());
            return -1;
        }
    }

    /**
     * Records a failed attempt. Returns the new attempt count. When the count
     * reaches {@code maxAttempts}, a lock is written and {@code true} is
     * returned via {@link #isLocked} on subsequent calls.
     *
     * @return number of failed attempts recorded so far for this username
     */
    public int recordFailedAttempt(String username) {
        try {
            String attemptsKey = ATTEMPTS_PREFIX + username;
            Long count = redis.opsForValue().increment(attemptsKey);

            // Set expiry on first failure of the window
            if (count != null && count == 1) {
                redis.expire(attemptsKey, Duration.ofMinutes(resetMinutes));
            }

            if (count != null && count >= maxAttempts) {
                redis.opsForValue().setIfAbsent(LOCK_PREFIX + username, "locked",
                        Duration.ofMinutes(lockoutMinutes));
                log.warn("Account locked for {} after {} failed attempts", username, count);
            }

            return count != null ? count.intValue() : 0;
        } catch (DataAccessException e) {
            log.warn("Failed to record login attempt for {}: {}", username, e.getMessage());
            return 0;
        }
    }

    /**
     * Resets the failure counter and removes any lock after a successful login.
     */
    public void resetAttempts(String username) {
        try {
            redis.delete(ATTEMPTS_PREFIX + username);
            redis.delete(LOCK_PREFIX + username);
            log.debug("Reset login attempts for {}", username);
        } catch (DataAccessException e) {
            log.warn("Failed to reset login attempts for {}: {}", username, e.getMessage());
        }
    }
}
