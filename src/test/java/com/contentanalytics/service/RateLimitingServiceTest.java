package com.contentanalytics.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Unit tests for the atomic Redis {@link RateLimitingService}. */
@DisplayName("RateLimitingService")
class RateLimitingServiceTest {

    private StringRedisTemplate redis;
    private RateLimitingService service;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        service = new RateLimitingService(redis);
    }

    private void scriptReturns(Long... values) {
        // Mockito 5 rejects calling thenReturn() repeatedly on one OngoingStubbing;
        // pass all answers to a single varargs call instead.
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                anyList(), anyString(), anyString()))
                .thenReturn(values[0], java.util.Arrays.copyOfRange(values, 1, values.length));
    }

    private void redisUnavailable() {
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                anyList(), anyString(), anyString()))
                .thenThrow(new RuntimeException("redis down"));
    }

    @Test
    @DisplayName("first request in window is allowed")
    void firstRequestAllowed() {
        scriptReturns(1L);

        RateLimitingService.RateLimitResult result = service.check("key", 5, 60);

        assertThat(result.allowed()).isTrue();
        assertThat(result.remaining()).isEqualTo(4L);
        verify(redis, times(1)).execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                anyList(), anyString(), anyString());
    }

    @Test
    @DisplayName("requests up to max are allowed")
    void upToMaxAllowed() {
        scriptReturns(1L, 2L, 3L, 4L, 5L);

        for (int i = 1; i <= 5; i++) {
            assertThat(service.check("key", 5, 60).allowed()).isTrue();
        }
    }

    @Test
    @DisplayName("request beyond max is rejected with retry-after")
    void beyondMaxRejected() {
        scriptReturns(6L);
        when(redis.getExpire(anyString())).thenReturn(42L);

        RateLimitingService.RateLimitResult result = service.check("key", 5, 60);

        assertThat(result.allowed()).isFalse();
        assertThat(result.remaining()).isZero();
        assertThat(result.retryAfterSeconds()).isEqualTo(42L);
    }

    @Test
    @DisplayName("Redis outage fails open instead of blocking all traffic")
    void redisOutageFailsOpen() {
        redisUnavailable();

        RateLimitingService.RateLimitResult result = service.check("key", 5, 60);

        assertThat(result.allowed()).isTrue();
        assertThat(result.remaining()).isEqualTo(5L);
    }

    @Test
    @DisplayName("getRemainingTtl returns -1 when no key exists")
    void ttlMinusOneWhenNoKey() {
        when(redis.getExpire(anyString())).thenReturn(null);

        assertThat(service.getRemainingTtl("key")).isEqualTo(-1L);
    }

    @Test
    @DisplayName("getRemainingTtl returns actual TTL when key exists")
    void ttlReturnsActual() {
        when(redis.getExpire(anyString())).thenReturn(42L);

        assertThat(service.getRemainingTtl("key")).isEqualTo(42L);
    }
}
