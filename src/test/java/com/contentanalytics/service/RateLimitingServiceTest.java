package com.contentanalytics.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RateLimitingService}.
 *
 * Covers allow-under-limit, reject-over-limit, expiry-reset-on-new-window,
 * and remaining-TTL reporting.
 */
@DisplayName("RateLimitingService")
class RateLimitingServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private RateLimitingService service;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        service = new RateLimitingService(redis);
    }

    @Test
    @DisplayName("first request in window is allowed")
    void firstRequestAllowed() {
        when(ops.increment(anyString())).thenReturn(1L);

        assertThat(service.isAllowed("key", 5, 60)).isTrue();
        verify(ops, times(1)).increment(anyString());
        verify(redis, atLeastOnce()).expire(anyString(), any());
    }

    @Test
    @DisplayName("requests up to max are allowed")
    void upToMaxAllowed() {
        when(ops.increment(anyString())).thenReturn(1L, 2L, 3L, 4L, 5L);

        for (int i = 1; i <= 5; i++) {
            assertThat(service.isAllowed("key", 5, 60)).isTrue();
        }
    }

    @Test
    @DisplayName("request beyond max is rejected")
    void beyondMaxRejected() {
        when(ops.increment(anyString())).thenReturn(6L);

        assertThat(service.isAllowed("key", 5, 60)).isFalse();
    }

    @Test
    @DisplayName("expire is only set on first request of window")
    void expireSetOnlyOnFirst() {
        when(ops.increment(anyString())).thenReturn(1L);

        service.isAllowed("key", 5, 60);
        verify(redis, times(1)).expire(anyString(), any());
    }

    @Test
    @DisplayName("no expire on subsequent requests in same window")
    void noExpireOnSubsequent() {
        when(ops.increment(anyString())).thenReturn(2L);

        service.isAllowed("key", 5, 60);
        verify(redis, never()).expire(anyString(), any());
    }

    @Test
    @DisplayName("getRemainingTtl returns -1 when no key exists")
    void ttlMinusOneWhenNoKey() {
        when(redis.getExpire(anyString())).thenReturn(null);

        assertThat(service.getRemainingTtl("key")).isEqualTo(-1);
    }

    @Test
    @DisplayName("getRemainingTtl returns actual TTL when key exists")
    void ttlReturnsActual() {
        when(redis.getExpire(anyString())).thenReturn(42L);

        assertThat(service.getRemainingTtl("key")).isEqualTo(42);
    }
}
