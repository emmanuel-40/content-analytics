package com.contentanalytics.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link IdempotencyService}.
 *
 * Covers cache-hit (duplicate returns cached response), cache-miss (first
 * request executes and caches), concurrent-pending (second caller waits for
 * first), and deserialization-fallback (corrupt cache re-executes).
 */
@DisplayName("IdempotencyService")
class IdempotencyServiceTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> ops;

    @Mock
    private IdempotencyService.IdempotencyOperation<String> op;

    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(redis.opsForValue()).thenReturn(ops);
        service = new IdempotencyService(redis, new ObjectMapper());
    }

    @Test
    @DisplayName("cache miss — executes operation and stores result")
    void cacheMissExecutesAndStores() {
        when(ops.get(anyString())).thenReturn(null);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        IdempotencyService.CachedResponse<String> result =
                service.execute("key-1", "endpoint", 24,
                        () -> new IdempotencyService.CachedResponse<>(200, Map.of(), "body"));

        assertThat(result.body()).isEqualTo("body");
        assertThat(result.status()).isEqualTo(200);
        // Verify result was cached (second set overwrites __PENDING__)
        verify(ops, atLeastOnce()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("cache hit — returns cached response without executing operation")
    void cacheHitReturnsWithoutExecute() {
        String json = "{\"status\":200,\"headers\":{},\"body\":\"cached-body\"}";
        when(ops.get(eq("idem:endpoint:key-1"))).thenReturn(json);

        IdempotencyService.CachedResponse<String> result =
                service.execute("key-1", "endpoint", 24, op);

        assertThat(result.body()).isEqualTo("cached-body");
        verifyNoInteractions(op);
    }

    @Test
    @DisplayName("concurrent pending — second caller fails closed instead of double-executing")
    void concurrentPendingDetectsInFlight() {
        when(ops.get(anyString())).thenReturn(null);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        // After SET NX fails, get keeps returning __PENDING__
        when(ops.get(eq("idem:endpoint:key-1"))).thenReturn("__PENDING__");

        assertThatThrownBy(() -> service.execute("key-1", "endpoint", 24, op))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already in progress");
        verifyNoInteractions(op);
    }

    @Test
    @DisplayName("Redis outage — operation still executes (DB guards remain authoritative)")
    void redisOutageFailsOpen() {
        when(redis.opsForValue()).thenThrow(new RuntimeException("redis down"));
        when(op.execute()).thenReturn(new IdempotencyService.CachedResponse<>(200, Map.of(), "body"));

        IdempotencyService.CachedResponse<String> result =
                service.execute("key-1", "endpoint", 24, op);

        assertThat(result.body()).isEqualTo("body");
        verify(op, times(1)).execute();
    }

    @Test
    @DisplayName("operation failure — claim is released for a later retry")
    void failureReleasesClaim() {
        when(ops.get(anyString())).thenReturn(null);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        assertThatThrownBy(() -> service.execute("key-1", "endpoint", 24, () -> {
            throw new IllegalStateException("provider down");
        })).isInstanceOf(IllegalStateException.class);

        verify(redis).delete("idem:endpoint:key-1");
    }

    @Test
    @DisplayName("corrupt cache — deserialization failure falls through to re-execute")
    void corruptCacheFallsThroughToExecute() {
        when(ops.get(anyString())).thenReturn("{not valid json}");
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        IdempotencyService.CachedResponse<String> result =
                service.execute("key-1", "endpoint", 24,
                        () -> new IdempotencyService.CachedResponse<>(201, Map.of(), "fresh"));

        assertThat(result.body()).isEqualTo("fresh");
        assertThat(result.status()).isEqualTo(201);
    }

    @Test
    @DisplayName("uses SET NX atomic check-and-set — only first caller wins")
    void usesSetIfAbsentForAtomicity() {
        when(ops.get(anyString())).thenReturn(null);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        service.execute("key-1", "endpoint", 24,
                () -> new IdempotencyService.CachedResponse<>(200, Map.of(), "body"));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);

        verify(ops, atLeastOnce()).setIfAbsent(keyCaptor.capture(), valueCaptor.capture(), ttlCaptor.capture());
        assertThat(keyCaptor.getValue()).startsWith("idem:endpoint:key-1");
        assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofHours(24));
    }
}
