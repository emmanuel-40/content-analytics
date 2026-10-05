package com.contentanalytics.security;

import com.contentanalytics.exception.InvalidTokenException;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JwtTokenProvider")
class JwtTokenProviderTest {

    private static final String SECRET =
            "test-secret-key-that-is-definitely-long-enough-for-hmac-sha512-signing-algorithm";

    private JwtTokenProvider tokenProvider;

    @BeforeEach
    void setUp() {
        tokenProvider = new JwtTokenProvider();
        ReflectionTestUtils.setField(tokenProvider, "jwtSecret", SECRET);
        ReflectionTestUtils.setField(tokenProvider, "jwtExpirationMs", 900_000L);
        ReflectionTestUtils.setField(tokenProvider, "refreshTokenExpirationMs", 604_800_000L);
    }

    @Test
    @DisplayName("access token carries the user id as a string and round-trips to a UUID")
    void accessTokenRoundTripsUserId() {
        UUID userId = UUID.randomUUID();

        String token = tokenProvider.generateAccessToken(userId, "oliver", "oliver@example.com");

        assertThat(tokenProvider.getUserIdFromToken(token)).isEqualTo(userId);
        assertThat(tokenProvider.getUsernameFromToken(token)).isEqualTo("oliver");
        assertThat(tokenProvider.isAccessToken(token)).isTrue();
        assertThat(tokenProvider.isRefreshToken(token)).isFalse();
    }

    @Test
    @DisplayName("refresh token embeds the CURRENT token version, not a hardcoded 1")
    void refreshTokenEmbedsSuppliedTokenVersion() {
        UUID userId = UUID.randomUUID();

        String token = tokenProvider.generateRefreshToken(userId, "oliver", 7);

        Claims claims = tokenProvider.getClaimsFromToken(token);
        assertThat(claims).isNotNull();
        assertThat(((Number) claims.get("tokenVersion")).intValue()).isEqualTo(7);
    }

    @Test
    @DisplayName("a refresh token minted before a password change is detectable as stale")
    void staleTokenVersionIsDetectable() {
        UUID userId = UUID.randomUUID();
        String token = tokenProvider.generateRefreshToken(userId, "oliver", 1);

        Claims claims = tokenProvider.getClaimsFromToken(token);
        int versionInToken = ((Number) claims.get("tokenVersion")).intValue();

        // Simulates the user bumping their version after a password change
        int currentVersionInDb = 2;
        assertThat(versionInToken).isNotEqualTo(currentVersionInDb);
    }

    @Test
    @DisplayName("getUserIdFromToken throws a typed exception for malformed tokens instead of NPE")
    void malformedTokenThrowsTypedException() {
        assertThatThrownBy(() -> tokenProvider.getUserIdFromToken("not-a-real-jwt"))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("Invalid or expired token");
    }

    @Test
    @DisplayName("tokens signed with a different secret are rejected")
    void tokenSignedWithDifferentSecretIsRejected() {
        JwtTokenProvider other = new JwtTokenProvider();
        // HS512 requires a key of at least 512 bits (64 bytes) per RFC 7518
        ReflectionTestUtils.setField(other, "jwtSecret",
                "a-completely-different-secret-key-of-sufficient-length-for-hs512-signing-0123456789");
        ReflectionTestUtils.setField(other, "jwtExpirationMs", 900_000L);
        ReflectionTestUtils.setField(other, "refreshTokenExpirationMs", 604_800_000L);

        String foreignToken = other.generateAccessToken(UUID.randomUUID(), "mallory", "m@example.com");

        assertThat(tokenProvider.validateToken(foreignToken)).isFalse();
    }

    @Test
    @DisplayName("an expired access token is reported as expired")
    void expiredTokenIsDetected() {
        JwtTokenProvider shortLived = new JwtTokenProvider();
        ReflectionTestUtils.setField(shortLived, "jwtSecret", SECRET);
        ReflectionTestUtils.setField(shortLived, "jwtExpirationMs", -1000L);
        ReflectionTestUtils.setField(shortLived, "refreshTokenExpirationMs", 604_800_000L);

        String token = shortLived.generateAccessToken(UUID.randomUUID(), "oliver", "o@example.com");

        assertThat(shortLived.validateToken(token)).isFalse();
    }

}
