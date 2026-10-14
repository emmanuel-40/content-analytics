package com.contentanalytics.security;

import com.contentanalytics.exception.InvalidTokenException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Clock;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtTokenProvider {

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Value("${app.jwt.expiration}")
    private long jwtExpirationMs; // Access token expiration (15 minutes recommended)

    @Value("${app.jwt.refresh.expiration}")
    private long refreshTokenExpirationMs; // Refresh token expiration (7 days)

    /**
     * Single source of "now" for stamping and checking token times.
     * <p>
     * Injected rather than calling {@code new Date()} / {@code System.currentTimeMillis()}
     * directly so tests can pin it to a fixed instant: issued-at and expiry become exact,
     * assertable values, and "is this token expired?" can be stepped across its boundary
     * without any sleep or wall-clock race.
     */
    private final Clock clock;

    /**
     * Generate Access Token (short-lived)
     * Used for API requests
     */
    public String generateAccessToken(UUID userId, String username, String email) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("type", "ACCESS");
        claims.put("email", email);
        claims.put("userId", userId.toString());

        return createToken(claims, username, jwtExpirationMs);
    }

    /**
     * Generate Refresh Token (long-lived)
     * Used to get new access tokens
     *
     * @param tokenVersion current token version of the user, embedded so that
     *                     bulk invalidation (e.g. password change) can be detected
     */
    public String generateRefreshToken(UUID userId, String username, Integer tokenVersion) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("type", "REFRESH");
        claims.put("userId", userId.toString());
        claims.put("tokenVersion", tokenVersion);

        return createToken(claims, username, refreshTokenExpirationMs);
    }

    /**
     * Create JWT token with claims
     */
    private String createToken(Map<String, Object> claims, String subject, long expirationTime) {
        // "now" comes from the injected Clock so issued-at / expiry are deterministic under test.
        Date now = new Date(clock.millis());
        Date expiryDate = new Date(now.getTime() + expirationTime);

        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes());

        return Jwts.builder()
                .setClaims(claims)
                .setSubject(subject)
                .setIssuedAt(now)
                .setExpiration(expiryDate)
                .signWith(key, SignatureAlgorithm.HS512)
                .compact();
    }

    /**
     * Extract claims from token.
     * <p>
     * Signature is always verified. Expiry, however, is <em>not</em> enforced here: a
     * validly-signed token whose {@code exp} is in the past still returns its claims, and
     * the accept/reject decision is made by {@link #isTokenExpired(Claims)} against the
     * injected {@link Clock}. That keeps "now" in one place (the clock) instead of the JWT
     * library silently consulting the JVM wall clock, so expiry is deterministic and testable.
     * A bad signature or malformed token still returns {@code null}; every real call path
     * ({@code JwtAuthenticationFilter}, {@code WebSocketInterceptor}, {@code AuthService})
     * gates on {@link #validateToken(String)} first, which combines both checks.
     */
    public Claims getClaimsFromToken(String token) {
        try {
            SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes());
            return Jwts.parserBuilder()
                    .setSigningKey(key)
                    .setClock(jjwtClock())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (ExpiredJwtException e) {
            // Signature verified and only the exp check failed - and that check now uses
            // our Clock via setClock(jjwtClock()). We still surface the claims so callers
            // like getTokenTimeToLive() can read an expired token's exp; the accept/reject
            // decision is made solely by validateToken()/isTokenExpired(), never here.
            log.debug("Token past its exp per the injected Clock; deferring verdict to validateToken()");
            return e.getClaims();
        } catch (Exception e) {
            log.error("Error extracting claims from token", e);
            return null;
        }
    }

    /**
     * Bridge our {@link Clock} into jjwt so the parser validates expiry against the same
     * time source used to stamp tokens - never the JVM wall clock. This is what makes
     * token expiry deterministic under a fixed clock in tests.
     */
    private io.jsonwebtoken.Clock jjwtClock() {
        return () -> Date.from(clock.instant());
    }

    /**
     * Get username from token
     */
    public String getUsernameFromToken(String token) {
        Claims claims = getClaimsFromToken(token);
        return claims != null ? claims.getSubject() : null;
    }

    /**
     * Get user ID from token
     */
    public UUID getUserIdFromToken(String token) {
        Claims claims = getClaimsFromToken(token);
        if (claims == null) {
            throw new InvalidTokenException("Invalid or expired token");
        }

        Object userIdObj = claims.get("userId");

        if (userIdObj == null) {
            throw new IllegalArgumentException("userId not found in token");
        }

        if (userIdObj instanceof String) {
            try {
                return UUID.fromString((String) userIdObj);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid userId format in token", e);
            }
        } else if (userIdObj instanceof UUID) {
            return (UUID) userIdObj;
        } else {
            throw new IllegalArgumentException("Invalid userId type in token: " + userIdObj.getClass());
        }
    }

    /**
     * Get token details including user ID
     */
    public Map<String, Object> getTokenDetails(String token) {
        Claims claims = getClaimsFromToken(token);
        if (claims == null) return null;

        Map<String, Object> details = new HashMap<>();
        details.put("userId", claims.get("userId"));
        details.put("username", claims.getSubject());
        details.put("email", claims.get("email"));
        details.put("type", claims.get("type"));

        return details;
    }


    /**
     * Validate token signature and expiration
     */
    public boolean validateToken(String token) {
        try {
            Claims claims = getClaimsFromToken(token);
            return claims != null && !isTokenExpired(claims);
        } catch (Exception e) {
            log.error("Token validation failed", e);
            return false;
        }
    }

    /**
     * Check if token is expired.
     * <p>
     * RFC 7519: a token MUST be rejected once the current time is at or past {@code exp},
     * so the comparison is {@code !exp.after(now)} (i.e. {@code now >= exp} is expired),
     * not {@code exp.before(now)} - which would wrongly accept the exact-expiration instant.
     */
    public boolean isTokenExpired(Claims claims) {
        return !claims.getExpiration().after(new Date(clock.millis()));
    }

    /**
     * Get remaining time before expiration (in seconds)
     */
    public long getTokenTimeToLive(String token) {
        Claims claims = getClaimsFromToken(token);
        if (claims == null) return 0;

        long expirationTime = claims.getExpiration().getTime();
        long currentTime = clock.millis();
        return (expirationTime - currentTime) / 1000;
    }

    /**
     * Check if token type is correct
     */
    public boolean isAccessToken(String token) {
        Claims claims = getClaimsFromToken(token);
        return claims != null && "ACCESS".equals(claims.get("type"));
    }

    public boolean isRefreshToken(String token) {
        Claims claims = getClaimsFromToken(token);
        return claims != null && "REFRESH".equals(claims.get("type"));
    }

}
