package com.contentanalytics.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JwtTokenProvider session claims")
class JwtTokenProviderSessionTest {

    private static final String SECRET =
            "test-secret-key-that-is-definitely-long-enough-for-hmac-sha512-signing-algorithm";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-15T09:30:00Z"), ZoneOffset.UTC);
    private static final UUID USER_ID = UUID.randomUUID();

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider(CLOCK);
        ReflectionTestUtils.setField(provider, "jwtSecret", SECRET);
        ReflectionTestUtils.setField(provider, "jwtExpirationMs", 900_000L);
        ReflectionTestUtils.setField(provider, "refreshTokenExpirationMs", 604_800_000L);
    }

    @Test
    @DisplayName("refresh tokens carry a unique id so rotations never collide on token_hash")
    void refreshTokensAreUniqueEvenInTheSameMillisecond() {
        String first = provider.generateRefreshToken(USER_ID, "oliver", 1);
        String second = provider.generateRefreshToken(USER_ID, "oliver", 1);

        assertThat(first).isNotEqualTo(second);
        assertThat(provider.getTokenIdFromToken(first)).isNotNull();
        assertThat(provider.getTokenIdFromToken(second))
                .isNotNull()
                .isNotEqualTo(provider.getTokenIdFromToken(first));
    }

    @Test
    @DisplayName("access tokens carry the token version used by the filter")
    void accessTokenCarriesVersion() {
        String token = provider.generateAccessToken(USER_ID, "oliver", "o@example.com", 3);

        assertThat(provider.getTokenVersionFromToken(token)).isEqualTo(3);
        assertThat(provider.hasCurrentTokenVersion(token, 3)).isTrue();
        assertThat(provider.hasCurrentTokenVersion(token, 4)).isFalse();
    }

    @Test
    @DisplayName("tokens without a version claim cannot be proven current")
    void missingVersionIsNotCurrent() {
        String token = provider.generateAccessToken(USER_ID, "oliver", "o@example.com");

        assertThat(provider.getTokenVersionFromToken(token)).isNull();
        assertThat(provider.hasCurrentTokenVersion(token, 1)).isFalse();
        assertThat(provider.hasCurrentTokenVersion(token, null)).isFalse();
    }
}
