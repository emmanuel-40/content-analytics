package com.contentanalytics.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link LoginAttemptService}.
 *
 * Covers lock detection, failed-attempt counting, lock creation at threshold,
 * remaining lock time, and reset on successful login.
 */
@DisplayName("LoginAttemptService")
class LoginAttemptServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private LoginAttemptService service;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        service = new LoginAttemptService(redis);
        ReflectionTestUtils.setField(service, "maxAttempts", 3);
        ReflectionTestUtils.setField(service, "lockoutMinutes", 30);
        ReflectionTestUtils.setField(service, "resetMinutes", 60);
    }

    @Test
    @DisplayName("isLocked false when no lock key exists")
    void notLockedInitially() {
        when(redis.hasKey(anyString())).thenReturn(false);
        assertThat(service.isLocked("alice")).isFalse();
    }

    @Test
    @DisplayName("isLocked true when lock key exists")
    void lockedWhenKeyExists() {
        when(redis.hasKey(anyString())).thenReturn(true);
        assertThat(service.isLocked("alice")).isTrue();
    }

    @Test
    @DisplayName("failed attempts increment and lock at threshold")
    void locksAtThreshold() {
        when(ops.increment(anyString())).thenReturn(1L, 2L, 3L);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(redis.hasKey("login:lock:alice")).thenReturn(true);

        assertThat(service.recordFailedAttempt("alice")).isEqualTo(1);
        assertThat(service.recordFailedAttempt("alice")).isEqualTo(2);
        assertThat(service.recordFailedAttempt("alice")).isEqualTo(3);

        verify(ops, times(1)).setIfAbsent(
                eq("login:lock:alice"), eq("locked"), any(Duration.class));
        assertThat(service.isLocked("alice")).isTrue();
    }

    @Test
    @DisplayName("expiry set on first failure of window")
    void expirySetOnFirstFailure() {
        when(ops.increment(anyString())).thenReturn(1L);

        service.recordFailedAttempt("alice");
        verify(redis, times(1)).expire(anyString(), eq(Duration.ofMinutes(60)));
    }

    @Test
    @DisplayName("resetAttempts clears both counter and lock")
    void resetClearsBoth() {
        service.resetAttempts("alice");
        verify(redis, times(1)).delete("login:attempts:alice");
        verify(redis, times(1)).delete("login:lock:alice");
    }

    @Test
    @DisplayName("getLockoutRemainingSeconds returns -1 when not locked")
    void ttlMinusOneWhenNotLocked() {
        when(redis.getExpire(anyString())).thenReturn(null);
        assertThat(service.getLockoutRemainingSeconds("alice")).isEqualTo(-1);
    }

    @Test
    @DisplayName("getLockoutRemainingSeconds returns actual TTL when locked")
    void ttlActualWhenLocked() {
        when(redis.getExpire(anyString())).thenReturn(1200L);
        assertThat(service.getLockoutRemainingSeconds("alice")).isEqualTo(1200);
    }
}
