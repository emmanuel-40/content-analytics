package com.contentanalytics.service;

import com.contentanalytics.entity.AuthProvider;
import com.contentanalytics.entity.User;
import com.contentanalytics.entity.UserRole;
import com.contentanalytics.entity.UserStatus;
import com.contentanalytics.exception.AccountLinkingException;
import com.contentanalytics.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Maps an external identity (Google) onto a row in {@code users}.
 * <p>
 * Three outcomes, in priority order:
 * <ol>
 *   <li><b>Returning OAuth user</b> - the Google {@code sub} is already linked to a
 *       row; refresh the last-login timestamp and reuse it.</li>
 *   <li><b>Account linking</b> - no linked row, but the (Google-verified) email
 *       already belongs to a local password account. Attach the Google identity to
 *       that row and mark the email verified, because Google vouched for it. The
 *       owner keeps their password, so they can still sign in either way.</li>
 *   <li><b>New account</b> - create a passwordless row seeded with the profile data
 *       Google returned.</li>
 * </ol>
 * <p>
 * Deliberate security property: an account already linked to a <i>different</i> Google
 * identity is never re-pointed. Google guarantees one {@code sub} per account and does
 * not let two accounts own the same address, so a mismatch means something is wrong
 * (Workspace aliasing, a compromised recovery address, or a bug) and the safe answer is
 * to refuse rather than silently hand the account over.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OAuthAccountProvisioner {

    /** Mirrors {@code users.username VARCHAR(100)}. */
    private static final int USERNAME_MAX_LENGTH = 100;

    /** Base username used when the email local-part sanitises to nothing usable. */
    private static final String FALLBACK_USERNAME = "user";

    /**
     * Upper bound on the {@code -1, -2, ...} suffix loop. 100 collisions on the same
     * derived username is already pathological, so bail out to a random suffix rather
     * than risk an unbounded scan.
     */
    private static final int MAX_SUFFIX_ATTEMPTS = 100;

    private final UserRepository userRepository;

    /**
     * Find or create the account for a Google identity and link the two together.
     *
     * @param subject    the immutable Google {@code sub} claim
     * @param email      Google's {@code email} claim (already checked for
     *                   {@code email_verified=true} by the caller)
     * @param givenName  Google {@code given_name}, may be blank
     * @param familyName Google {@code family_name}, may be blank
     * @return the persisted user, always linked to Google
     */
    @Transactional
    public User provisionGoogleAccount(String subject, String email, String givenName, String familyName) {

        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);

        return userRepository.findByProviderAndProviderSubject(AuthProvider.GOOGLE, subject)
                .map(this::touch)
                .or(() -> linkByEmailIfPresent(subject, normalizedEmail))
                .orElseGet(() -> createGoogleUser(subject, normalizedEmail, givenName, familyName));
    }

    /** Returning user: just record that the sign-in happened. */
    private User touch(User user) {
        user.setLastLoginAt(LocalDateTime.now());
        return userRepository.save(user);
    }

    private Optional<User> linkByEmailIfPresent(String subject, String email) {
        return userRepository.findByEmail(email).map(existing -> link(existing, subject));
    }

    /**
     * Attach the Google identity to an existing local account that owns the same
     * (Google-verified) email.
     */
    private User link(User existing, String subject) {
        if (existing.getProviderSubject() != null && !existing.getProviderSubject().equals(subject)) {
            log.error("Refusing to re-link user {}: email {} is already bound to another Google identity",
                    existing.getUsername(), existing.getEmail());
            throw new AccountLinkingException(
                    "This email address is already linked to a different Google account. "
                    + "Sign out of Google and use the account that owns this address.");
        }

        log.info("Linking Google identity to existing account: {}", existing.getUsername());

        existing.setProvider(AuthProvider.GOOGLE);
        existing.setProviderSubject(subject);

        // Google vouched for this address, so a previously unverified local account can
        // be marked verified and its owner never needs the verification email.
        if (!Boolean.TRUE.equals(existing.getEmailVerified())) {
            existing.setEmailVerified(true);
            existing.setEmailVerifiedAt(LocalDateTime.now());
        }

        existing.setLastLoginAt(LocalDateTime.now());
        return userRepository.save(existing);
    }

    private User createGoogleUser(String subject, String email, String givenName, String familyName) {
        String username = deriveUsername(email);

        User user = User.builder()
                .username(username)
                .email(email)
                // No password: this account can only be opened through Google until the
                // owner deliberately sets one via "forgot password".
                .password(null)
                .firstName(blankToNull(givenName))
                .lastName(blankToNull(familyName))
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .tokenVersion(1)              // the first access/refresh pair is version 1
                .emailVerified(true)          // Google already verified the address
                .emailVerifiedAt(LocalDateTime.now())
                .provider(AuthProvider.GOOGLE)
                .providerSubject(subject)
                .lastLoginAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        try {
            User saved = userRepository.save(user);
            log.info("Created Google-only account: {}", saved.getUsername());
            return saved;
        } catch (DataIntegrityViolationException e) {
            // Two first-time Google sign-ups raced on the same derived username.
            // Retry once with a random suffix so the sign-in still succeeds.
            log.warn("Username collision on '{}' - retrying with a random suffix", username, e);
            return userRepository.save(withUsername(user, fit(username + "-" + randomSuffix())));
        }
    }

    /**
     * Build a unique username from the email local-part, e.g. {@code jane.doe} ->
     * {@code jane.doe}, or {@code jane.doe-1} when that one is already taken.
     */
    private String deriveUsername(String email) {
        String localPart = email;
        int at = email.indexOf('@');
        if (at > 0) {
            localPart = email.substring(0, at);
        }

        String base = localPart.replaceAll("[^a-z0-9._-]", "").toLowerCase(Locale.ROOT);
        if (base.isBlank()) {
            base = FALLBACK_USERNAME;
        }
        base = fit(base);

        for (int attempt = 0; attempt <= MAX_SUFFIX_ATTEMPTS; attempt++) {
            String candidate = attempt == 0 ? base : fit(base + "-" + attempt);
            if (!userRepository.existsByUsername(candidate)) {
                return candidate;
            }
        }

        // Pathological case: stop scanning and let a random suffix decide.
        return fit(base + "-" + randomSuffix());
    }

    private User withUsername(User user, String username) {
        user.setUsername(username);
        return user;
    }

    private String fit(String value) {
        return value.length() > USERNAME_MAX_LENGTH ? value.substring(0, USERNAME_MAX_LENGTH) : value;
    }

    private String randomSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > 255 ? trimmed.substring(0, 255) : trimmed;
    }
}
