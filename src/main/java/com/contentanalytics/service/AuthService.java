package com.contentanalytics.service;

import com.contentanalytics.dto.LoginRequestDto;
import com.contentanalytics.dto.RegisterRequestDto;
import com.contentanalytics.dto.TokenRefreshRequestDto;
import com.contentanalytics.dto.TokenResponseDto;
import com.contentanalytics.entity.*;
import com.contentanalytics.exception.InvalidTokenException;
import com.contentanalytics.exception.UserAlreadyExistsException;
import com.contentanalytics.exception.UserNotFoundException;
import com.contentanalytics.repository.EmailVerificationTokenRepository;
import com.contentanalytics.repository.PasswordResetTokenRepository;
import com.contentanalytics.repository.RefreshTokenRepository;
import com.contentanalytics.repository.UserRepository;
import com.contentanalytics.security.JwtTokenProvider;
import com.contentanalytics.util.TokenGenerationUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class AuthService {

    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptService loginAttemptService;

    private final EmailService emailService;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenRevocationService refreshTokenRevocationService;
    private final TokenGenerationUtil tokenGenerationUtil;

    @Value("${app.frontend.url:http://localhost:5173}")
    private String frontendUrl;

    @Value("${app.token.email-verification-expiry-hours:24}")
    private int emailVerificationExpiryHours;

    @Value("${app.token.password-reset-expiry-hours:1}")
    private int passwordResetExpiryHours;

    @Value("${app.jwt.refresh.expiration}")
    private long refreshTokenExpirationMs;

    /**
     * Register new user (updated with email verification)
     */
    public void register(RegisterRequestDto request) {
        // Check if username exists
        if (userRepository.existsByUsername(request.getUsername())) {
            log.warn("Registration attempt with existing username: {}", request.getUsername());
            throw new UserAlreadyExistsException("Username already exists");
        }

        // Check if email exists
        if (userRepository.existsByEmail(request.getEmail())) {
            log.warn("Registration attempt with existing email: {}", request.getEmail());
            throw new UserAlreadyExistsException("Email already exists");
        }

        // Create new user
        User user = User.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .tokenVersion(1)
                .emailVerified(false)                    // NOT verified yet
                .provider(AuthProvider.LOCAL)             // password account
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        user = userRepository.save(user);
        log.info("New user registered: {} ({})", user.getUsername(), user.getId());

        // Generate email verification token
        String verificationToken = tokenGenerationUtil.generateSecureToken();
        LocalDateTime expiryDate = LocalDateTime.now().plus(emailVerificationExpiryHours, ChronoUnit.HOURS);

        EmailVerificationToken token = EmailVerificationToken.builder()
                .token(verificationToken)
                .user(user)
                .expiryDate(expiryDate)
                .used(false)
                .createdAt(LocalDateTime.now())
                .build();

        emailVerificationTokenRepository.save(token);
        log.debug("Email verification token created for user: {}", user.getId());

        // A mail failure must never roll back a committed registration. Defer it
        // until the transaction that created the user and token has committed.
        String email = user.getEmail();
        String displayName = user.getFirstName() != null ? user.getFirstName() : user.getUsername();
        String verificationLink = frontendUrl + "/api/auth/verify-email?token=" + verificationToken;
        afterCommit(() -> {
            emailService.sendEmailVerificationLink(email, displayName, verificationLink);
            log.info("Verification email sent to: {}", email);
        });

        log.info("User registration complete - awaiting email verification: {}", user.getUsername());

        // NO tokens returned - user must verify email then login
    }

    /**
     * Verify email with token. The claim is a conditional UPDATE so two
     * simultaneous clicks cannot both verify the same link.
     */
    public void verifyEmail(String token) {
        log.info("Email verification request with token");

        EmailVerificationToken verificationToken = emailVerificationTokenRepository
                .findByToken(token)
                .orElseThrow(() -> {
                    log.warn("Invalid email verification token");
                    return new InvalidTokenException("Invalid or expired verification token");
                });

        LocalDateTime now = LocalDateTime.now();
        if (Boolean.TRUE.equals(verificationToken.getUsed())
                || verificationToken.getExpiryDate() == null
                || !verificationToken.getExpiryDate().isAfter(now)) {
            log.warn("Reused or expired email verification token for user: {}", verificationToken.getUser().getId());
            emailVerificationTokenRepository.invalidateAllForUser(verificationToken.getUser().getId());
            throw new InvalidTokenException("Invalid or expired verification token");
        }

        User user = verificationToken.getUser();

        if (Boolean.TRUE.equals(user.getEmailVerified())) {
            log.warn("Email already verified for user: {}", user.getId());
            emailVerificationTokenRepository.invalidateAllForUser(user.getId());
            throw new IllegalArgumentException("Email already verified");
        }

        int claimed = emailVerificationTokenRepository.claimToken(token, now);
        if (claimed != 1) {
            // Another request won the race. Treat it like a replay: no second
            // welcome mail, no second state transition, kill the siblings.
            log.warn("Concurrent reuse of email verification token for user: {}", user.getId());
            emailVerificationTokenRepository.invalidateAllForUser(user.getId());
            throw new InvalidTokenException("Invalid or expired verification token");
        }

        // One link is enough - no valid sibling may survive a successful verification.
        emailVerificationTokenRepository.invalidateAllForUser(user.getId());

        user.setEmailVerified(true);
        user.setEmailVerifiedAt(now);
        userRepository.save(user);

        log.info("Email verified for user: {}", user.getId());

        String email = user.getEmail();
        String displayName = user.getFirstName() != null ? user.getFirstName() : user.getUsername();
        afterCommit(() -> {
            emailService.sendWelcomeEmail(email, displayName);
            log.info("Welcome email sent to: {}", email);
        });
    }

    /**
     * Resend email verification
     */
    public void resendVerificationEmail(String email) {
        log.info("Resend verification email request for: {}", email);

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        if (Boolean.TRUE.equals(user.getEmailVerified())) {
            throw new IllegalArgumentException("Email is already verified");
        }

        // Atomic bulk invalidation - no read-modify-write window on old links.
        emailVerificationTokenRepository.invalidateAllForUser(user.getId());

        String verificationToken = tokenGenerationUtil.generateSecureToken();
        LocalDateTime expiryDate = LocalDateTime.now().plus(emailVerificationExpiryHours, ChronoUnit.HOURS);

        EmailVerificationToken token = EmailVerificationToken.builder()
                .token(verificationToken)
                .user(user)
                .expiryDate(expiryDate)
                .used(false)
                .createdAt(LocalDateTime.now())
                .build();

        emailVerificationTokenRepository.save(token);

        String verificationLink = frontendUrl + "/verify-email?token=" + verificationToken;
        String recipient = user.getEmail();
        String displayName = user.getFirstName() != null ? user.getFirstName() : user.getUsername();
        afterCommit(() -> {
            emailService.sendEmailVerificationLink(recipient, displayName, verificationLink);
            log.info("Verification email resent to: {}", recipient);
        });
    }

    /**
     * Request password reset. Any older reset link is invalidated first so only
     * the newest one can ever be consumed.
     */
    public void requestPasswordReset(String email) {
        log.info("Password reset request for: {}", email);

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        LocalDateTime now = LocalDateTime.now();
        passwordResetTokenRepository.invalidateAllForUser(user.getId(), now);

        String resetToken = tokenGenerationUtil.generateSecureToken();
        LocalDateTime expiryDate = now.plus(passwordResetExpiryHours, ChronoUnit.HOURS);

        PasswordResetToken token = PasswordResetToken.builder()
                .token(resetToken)
                .user(user)
                .expiryDate(expiryDate)
                .used(false)
                .createdAt(now)
                .build();

        passwordResetTokenRepository.save(token);
        log.debug("Password reset token created for user: {}", user.getId());

        String resetLink = frontendUrl + "/reset-password?token=" + resetToken;
        String recipient = user.getEmail();
        String displayName = user.getFirstName() != null ? user.getFirstName() : user.getUsername();
        afterCommit(() -> {
            emailService.sendPasswordResetLink(recipient, displayName, resetLink);
            log.info("Password reset email sent to: {}", recipient);
        });
    }

    /**
     * Reset password with token. The token is claimed with a conditional UPDATE
     * before any password state is changed, so the link is strictly single-use.
     */
    public void resetPassword(String token, String newPassword) {
        log.info("Password reset request with token");

        if (newPassword == null || newPassword.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters");
        }

        PasswordResetToken resetToken = passwordResetTokenRepository
                .findByToken(token)
                .orElseThrow(() -> {
                    log.warn("Invalid password reset token");
                    return new InvalidTokenException("Invalid or expired reset token");
                });

        LocalDateTime now = LocalDateTime.now();
        if (Boolean.TRUE.equals(resetToken.getUsed())
                || resetToken.getExpiryDate() == null
                || !resetToken.getExpiryDate().isAfter(now)) {
            log.warn("Reused or expired password reset token for user: {}", resetToken.getUser().getId());
            passwordResetTokenRepository.invalidateAllForUser(resetToken.getUser().getId(), now);
            throw new InvalidTokenException("Invalid or expired reset token");
        }

        User user = resetToken.getUser();

        int claimed = passwordResetTokenRepository.claimToken(token, now);
        if (claimed != 1) {
            log.warn("Concurrent reuse of password reset token for user: {}", user.getId());
            passwordResetTokenRepository.invalidateAllForUser(user.getId(), now);
            throw new InvalidTokenException("Invalid or expired reset token");
        }

        // Kill any other outstanding reset links for this account.
        passwordResetTokenRepository.invalidateAllForUser(user.getId(), now);

        // Password changes must close all existing sessions, not just this one.
        refreshTokenRepository.revokeAllForUser(user.getId(), now, RefreshTokenStatus.REVOKED);
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setLastPasswordResetAt(now);
        user.setLastPasswordChangeAt(now);
        bumpTokenVersion(user);
        userRepository.save(user);

        log.info("Password reset successfully for user: {}", user.getId());
    }

    /**
     * Updated login to check email verification
     */
    public TokenResponseDto login(LoginRequestDto request) {
        String username = request.getUsername();

        // Hard lockout check — reject immediately if account is locked
        if (loginAttemptService.isLocked(username)) {
            long remaining = loginAttemptService.getLockoutRemainingSeconds(username);
            log.warn("Login blocked — account locked: {} (retry in {}s)", username, remaining);
            throw new InvalidTokenException(
                    "Account is locked due to multiple failed login attempts. Try again in " + remaining + " seconds.");
        }

        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> {
                    log.warn("Login attempt with non-existent username: {}", request.getUsername());
                    return new UserNotFoundException("Invalid credentials");
                });

        // Check if user is active
        if (user.getStatus() != UserStatus.ACTIVE) {
            log.warn("Login attempt with inactive user: {} (status: {})", user.getUsername(), user.getStatus());
            throw new InvalidTokenException("User account is not active");
        }

        // NEW: Check if email is verified
        if (!Boolean.TRUE.equals(user.getEmailVerified())) {
            log.warn("Login attempt with unverified email: {}", user.getUsername());
            throw new InvalidTokenException("Please verify your email before logging in");
        }

        // Google-only accounts have no password at all (users.password is NULL since
        // V11). Guard explicitly: BCrypt would just return false and the user would be
        // told "invalid credentials" instead of "use Google sign-in", which is not
        // actionable feedback.
        if (user.getPassword() == null || user.getPassword().isBlank()) {
            log.warn("Password login attempted against passwordless account: {}", user.getUsername());
            throw new InvalidTokenException(
                    "This account signs in with Google. Use Google sign-in, or reset your password to add one.");
        }

        // Verify password
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            loginAttemptService.recordFailedAttempt(username);
            log.warn("Failed login attempt for user: {}", username);
            throw new InvalidTokenException("Invalid credentials");
        }

        // Update last login time
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);
        log.info("User logged in: {}", user.getUsername());
        loginAttemptService.resetAttempts(username);

        return generateTokenResponse(user);
    }

    /**
     * Rotate a refresh token. The presented token is claimed with a single
     * conditional UPDATE; only the winner may persist a replacement. A second
     * presentation of an already-used/revoked token is treated as theft and
     * revokes the whole family plus the user's token version.
     */
    public TokenResponseDto refreshAccessToken(TokenRefreshRequestDto request) {
        String rawToken = request == null ? null : request.getRefreshToken();
        if (rawToken == null || rawToken.isBlank()
                || !jwtTokenProvider.validateToken(rawToken)
                || !jwtTokenProvider.isRefreshToken(rawToken)) {
            log.warn("Invalid refresh token used");
            throw new InvalidTokenException("Invalid or expired refresh token");
        }

        final UUID userId;
        final Integer presentedVersion;
        try {
            userId = jwtTokenProvider.getUserIdFromToken(rawToken);
            presentedVersion = jwtTokenProvider.getTokenVersionFromToken(rawToken);
        } catch (RuntimeException e) {
            log.warn("Malformed refresh token claims");
            throw new InvalidTokenException("Invalid or expired refresh token");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> {
                    log.warn("Refresh token used for non-existent user: {}", userId);
                    return new UserNotFoundException("User not found");
                });

        String tokenHash = hashRefreshToken(rawToken);
        RefreshToken stored = refreshTokenRepository.findByTokenHash(tokenHash).orElse(null);
        if (stored == null) {
            // A correctly signed token with no server-side record cannot be
            // trusted. Close every session so the caller must sign in again.
            log.warn("Refresh token has no persisted record for user: {}", userId);
            // REQUIRES_NEW: this revocation must commit even though we throw below.
            refreshTokenRevocationService.revokeAllAndBumpVersion(userId);
            throw new InvalidTokenException("Invalid or expired refresh token");
        }

        LocalDateTime now = LocalDateTime.now();
        UUID familyId = stored.getFamilyId();

        boolean compromised = user.getStatus() != UserStatus.ACTIVE
                || presentedVersion == null
                || !presentedVersion.equals(user.getTokenVersion())
                || stored.getStatus() != RefreshTokenStatus.ACTIVE
                || Boolean.TRUE.equals(stored.getUsed())
                || Boolean.TRUE.equals(stored.getRevoked())
                || stored.getExpiresAt() == null
                || !stored.getExpiresAt().isAfter(now)
                || !userId.equals(stored.getUser().getId());

        if (compromised) {
            log.warn("Refresh token replay/invalid state detected for user {} (family {})", userId, familyId);
            refreshTokenRevocationService.revokeFamilyAndBumpVersion(userId, familyId);
            throw new InvalidTokenException("Refresh token has been invalidated - please login again");
        }

        // Generate the replacement first: its id is written onto the old row by
        // the claim, and its hash must be persisted in the same transaction.
        UUID replacementId = UUID.randomUUID();
        String newAccessToken = jwtTokenProvider.generateAccessToken(
                user.getId(), user.getUsername(), user.getEmail(), user.getTokenVersion());
        String newRefreshToken = jwtTokenProvider.generateRefreshToken(
                user.getId(), user.getUsername(), user.getTokenVersion());

        int claimed = refreshTokenRepository.claimToken(
                tokenHash, now, replacementId, RefreshTokenStatus.ACTIVE, RefreshTokenStatus.USED);
        if (claimed != 1) {
            // A concurrent request won this token - exactly the replay signature.
            log.warn("Concurrent refresh detected for user {} (family {})", userId, familyId);
            refreshTokenRevocationService.revokeFamilyAndBumpVersion(userId, familyId);
            throw new InvalidTokenException("Refresh token reuse detected - all sessions revoked");
        }

        persistRefreshToken(user, newRefreshToken, familyId, replacementId);

        log.info("Access token refreshed for user: {}", user.getUsername());
        return TokenResponseDto.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .tokenType("Bearer")
                .expiresIn(jwtTokenProvider.getTokenTimeToLive(newAccessToken))
                .userId(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .build();
    }

    /**
     * Logout with a token string (refresh JWT, access JWT, or - for legacy
     * callers - a user id). Resolves the user where possible and kills sessions.
     */
    @CacheEvict(value = "user", key = "#tokenOrUserId")
    public void logout(String tokenOrUserId) {
        if (tokenOrUserId == null || tokenOrUserId.isBlank()) {
            log.debug("Logout called without a token; cookies will be cleared client-side");
            return;
        }

        try {
            // Legacy service callers passed the user id as a String.
            logout(UUID.fromString(tokenOrUserId), null);
            return;
        } catch (IllegalArgumentException ignored) {
            // Not a UUID - continue treating it as a JWT.
        }

        UUID userId = null;
        try {
            if (jwtTokenProvider.validateToken(tokenOrUserId)) {
                userId = jwtTokenProvider.getUserIdFromToken(tokenOrUserId);
            }
        } catch (RuntimeException e) {
            log.debug("Logout token is not a usable JWT");
        }

        if (userId == null) {
            log.warn("Logout called with an unrecognised token; nothing to revoke server-side");
            return;
        }
        logout(userId, tokenOrUserId);
    }

    /**
     * Revoke the presented refresh token, all of the user's refresh tokens, and
     * bump the token version so already-issued access tokens stop working too.
     */
    @CacheEvict(value = "user", key = "#userId")
    public void logout(UUID userId, String refreshToken) {
        LocalDateTime now = LocalDateTime.now();
        if (refreshToken != null && !refreshToken.isBlank()) {
            refreshTokenRepository.revokeByTokenHash(
                    hashRefreshToken(refreshToken), now, RefreshTokenStatus.REVOKED);
        }
        revokeAllSessions(userRepository.findById(userId).orElse(null));
        log.info("User logged out: {}", userId);
    }

    /**
     * Invalidate all tokens for a user (password change, security incident).
     * Increments tokenVersion - all old tokens become invalid.
     */
    @CacheEvict(value = "user", key = "#userId")
    public void invalidateAllTokens(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found"));
        revokeAllSessions(user);
        log.info("All tokens invalidated for user: {} (new version: {})", user.getUsername(), user.getTokenVersion());
    }

    /**
     * Get user by ID (cached for performance)
     */
    @Cacheable(value = "user", key = "#userId", unless = "#result == null")
    public User getUserById(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found"));
    }

    /**
     * Get current authenticated user
     */
    public User getCurrentUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new UserNotFoundException("User not found"));
    }

    /**
     * Mint the access/refresh token pair for a user whose identity has already been
     * established (password login and Google sign-in share this path).
     */
    public TokenResponseDto issueTokens(User user) {
        return generateTokenResponse(user);
    }

    // ---------------------------------------------------------------------
    // Token plumbing
    // ---------------------------------------------------------------------

    private TokenResponseDto generateTokenResponse(User user) {
        return generateTokenResponse(user, UUID.randomUUID());
    }

    private TokenResponseDto generateTokenResponse(User user, UUID familyId) {
        User sessionUser = user == null || user.getId() == null
                ? user
                : userRepository.findById(user.getId()).orElse(user);

        if (sessionUser == null || sessionUser.getStatus() != UserStatus.ACTIVE) {
            throw new InvalidTokenException("User account is not active");
        }

        String accessToken = jwtTokenProvider.generateAccessToken(
                sessionUser.getId(), sessionUser.getUsername(), sessionUser.getEmail(), sessionUser.getTokenVersion());
        String refreshToken = jwtTokenProvider.generateRefreshToken(
                sessionUser.getId(), sessionUser.getUsername(), sessionUser.getTokenVersion());
        persistRefreshToken(sessionUser, refreshToken, familyId, null);

        long expiresIn = jwtTokenProvider.getTokenTimeToLive(accessToken);

        return TokenResponseDto.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(expiresIn)
                .userId(sessionUser.getId())
                .username(sessionUser.getUsername())
                .email(sessionUser.getEmail())
                .build();
    }

    private void persistRefreshToken(User user, String rawRefreshToken, UUID familyId, UUID replacedBy) {
        RefreshToken entity = RefreshToken.builder()
                .id(UUID.randomUUID())
                .user(user)
                .tokenHash(hashRefreshToken(rawRefreshToken))
                .familyId(familyId)
                .status(RefreshTokenStatus.ACTIVE)
                .used(false)
                .revoked(false)
                .expiresAt(LocalDateTime.now().plus(refreshTokenExpirationMs, ChronoUnit.MILLIS))
                .replacedBy(replacedBy)
                .build();
        refreshTokenRepository.save(entity);
    }

    /** Only the digest is ever persisted - a DB dump cannot be replayed as a token. */
    private String hashRefreshToken(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }

    private void revokeAllSessions(User user) {
        if (user == null) {
            return;
        }
        refreshTokenRepository.revokeAllForUser(user.getId(), LocalDateTime.now(), RefreshTokenStatus.REVOKED);
        bumpTokenVersion(user);
        userRepository.save(user);
    }

    private void bumpTokenVersion(User user) {
        int current = user.getTokenVersion() == null ? 0 : user.getTokenVersion();
        user.setTokenVersion(current + 1);
    }

    /**
     * Side effects such as sending mail belong outside the database transaction:
     * otherwise a mail failure rolls back a reset link that was already committed
     * (or, worse, an email is sent for a transaction that later fails).
     */
    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    runSafely(action);
                }
            });
        } else {
            // Unit tests / non-transactional callers: behave like an immediate commit.
            runSafely(action);
        }
    }

    private void runSafely(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.error("Post-commit action failed", e);
        }
    }
}
