package com.contentanalytics.security;

import com.contentanalytics.entity.User;
import com.contentanalytics.entity.UserStatus;
import com.contentanalytics.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Bridges the stateless JWT world to the server-side session state.
 *
 * Signature and expiry alone are not enough after a logout or password
 * change: the access token must also carry the token version that is still
 * current, and its owner must still be ACTIVE. The lookup happens only when a
 * bearer/cookie token is actually presented, so anonymous public endpoints
 * never pay for a database round trip.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TokenSessionValidator {

    private final UserRepository userRepository;
    private final JwtTokenProvider tokenProvider;

    public boolean isValidSession(String token) {
        if (token == null || !tokenProvider.validateToken(token) || !tokenProvider.isAccessToken(token)) {
            return false;
        }

        final UUID userId;
        try {
            userId = tokenProvider.getUserIdFromToken(token);
        } catch (RuntimeException e) {
            log.debug("Rejecting access token with unusable userId claim");
            return false;
        }

        Optional<User> found;
        try {
            found = userRepository.findById(userId);
        } catch (RuntimeException e) {
            log.warn("Could not load user {} while validating access token", userId, e);
            return false;
        }

        if (found.isEmpty()) {
            log.debug("Rejecting access token for deleted user {}", userId);
            return false;
        }

        User user = found.get();
        if (user.getStatus() != UserStatus.ACTIVE) {
            log.debug("Rejecting access token for non-active user {} (status {})", userId, user.getStatus());
            return false;
        }

        if (!tokenProvider.hasCurrentTokenVersion(token, user.getTokenVersion())) {
            log.debug("Rejecting stale access token for user {} (token version no longer current)", userId);
            return false;
        }

        return true;
    }
}
