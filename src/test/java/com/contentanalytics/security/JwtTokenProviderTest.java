package com.contentanalytics.security;

import com.contentanalytics.exception.InvalidTokenException;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JwtTokenProvider")
class JwtTokenProviderTest {

    private static final String SECRET =
            "test-secret-key-that-is-definitely-long-enough-for-hmac-sha512-signing-algorithm";

    // Deterministic time: the provider stamps issued-at/expiry from this exact instant,
    // so those values are assertable to the millisecond and expiry can be crossed by
    // moving the Clock instead of sleeping or faking a negative TTL.
    private static final Instant FIXED_NOW = Instant.parse("2026-01-15T09:30:00Z");
    private static final Clock CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    private static final long ACCESS_TTL_MS = 900_000L;       // 15 minutes
    private static final long REFRESH_TTL_MS = 604_800_000L;  // 7 days

    private JwtTokenProvider tokenProvider;

    @BeforeEach
    void setUp() {
        tokenProvider = providerWith(CLOCK, ACCESS_TTL_MS);
    }

    /** Build a provider bound to a specific clock + TTLs; @Value fields stay reflective. */
    private JwtTokenProvider providerWith(Clock clock, long accessTtlMs) {
        JwtTokenProvider provider = new JwtTokenProvider(clock);
        ReflectionTestUtils.setField(provider, "jwtSecret", SECRET);
        ReflectionTestUtils.setField(provider, "jwtExpirationMs", accessTtlMs);
        ReflectionTestUtils.setField(provider, "refreshTokenExpirationMs", REFRESH_TTL_MS);
        return provider;
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
    @DisplayName("issued-at and expiry are stamped from the injected Clock, to the millisecond")
    void issuedAtAndExpiryAreExact() {
        UUID userId = UUID.randomUUID();

        String token = tokenProvider.generateAccessToken(userId, "oliver", "oliver@example.com");
        Claims claims = tokenProvider.getClaimsFromToken(token);

        // Because the clock is fixed, these are exact - no tolerance window, no wall-clock race.
        assertThat(claims.getIssuedAt().toInstant()).isEqualTo(FIXED_NOW);
        assertThat(claims.getExpiration().toInstant())
                .isEqualTo(FIXED_NOW.plusMillis(ACCESS_TTL_MS));
    }

    @Test
    @DisplayName("a token is valid before expiry and expired just after, purely by moving the Clock")
    void expiryBoundaryIsDeterministic() {
        UUID userId = UUID.randomUUID();
        String token = tokenProvider.generateAccessToken(userId, "oliver", "o@example.com");

        // 1 ms before expiry -> still valid.
        Clock justBefore = Clock.fixed(FIXED_NOW.plusMillis(ACCESS_TTL_MS - 1), ZoneOffset.UTC);
        assertThat(providerWith(justBefore, ACCESS_TTL_MS).validateToken(token)).isTrue();

        // 1 ms after expiry -> invalid. Same token, only the clock moved.
        Clock justAfter = Clock.fixed(FIXED_NOW.plusMillis(ACCESS_TTL_MS + 1), ZoneOffset.UTC);
        assertThat(providerWith(justAfter, ACCESS_TTL_MS).validateToken(token)).isFalse();
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
        JwtTokenProvider other = new JwtTokenProvider(CLOCK);
        // HS512 requires a key of at least 512 bits (64 bytes) per RFC 7518
        ReflectionTestUtils.setField(other, "jwtSecret",
                "a-completely-different-secret-key-of-sufficient-length-for-hs512-signing-0123456789");
        ReflectionTestUtils.setField(other, "jwtExpirationMs", ACCESS_TTL_MS);
        ReflectionTestUtils.setField(other, "refreshTokenExpirationMs", REFRESH_TTL_MS);

        String foreignToken = other.generateAccessToken(UUID.randomUUID(), "mallory", "m@example.com");

        assertThat(tokenProvider.validateToken(foreignToken)).isFalse();
    }

    @Test
    @DisplayName("an expired access token is reported as expired")
    void expiredTokenIsDetected() {
        // Same trick as the boundary test: mint now, then validate with the clock moved
        // past the expiry instant - deterministic, no negative TTL needed.
        UUID userId = UUID.randomUUID();
        String token = tokenProvider.generateAccessToken(userId, "oliver", "o@example.com");

        Clock afterExpiry = Clock.fixed(FIXED_NOW.plusMillis(ACCESS_TTL_MS + 60_000), ZoneOffset.UTC);
        JwtTokenProvider validator = providerWith(afterExpiry, ACCESS_TTL_MS);

        assertThat(validator.validateToken(token)).isFalse();
        assertThat(validator.getTokenTimeToLive(token)).isNegative();
    }

}
