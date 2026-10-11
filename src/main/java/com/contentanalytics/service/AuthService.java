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
import com.contentanalytics.repository.UserRepository;
import com.contentanalytics.security.JwtTokenProvider;
import com.contentanalytics.util.TokenGenerationUtil;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class AuthService {

    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;

    private final EmailService emailService;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
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

        // Send verification email
        String verificationLink = frontendUrl + "/api/auth/verify-email?token=" + verificationToken;  // ✅ Simplified link
        try {
            emailService.sendEmailVerificationLink(
                    user.getEmail(),
                    user.getFirstName() != null ? user.getFirstName() : user.getUsername(),
                    verificationLink
            );
            log.info("Verification email sent to: {}", user.getEmail());
        } catch (Exception e) {
            log.error("Failed to send verification email", e);
            // Don't fail registration if email fails
        }

        log.info("User registration complete - awaiting email verification: {}", user.getUsername());

        // NO tokens returned - user must verify email then login
    }

    /**
     * Verify email with token
     */
    @Transactional
    public void verifyEmail(String token) {
        log.info("Email verification request with token");

        // Find valid token
        EmailVerificationToken verificationToken = emailVerificationTokenRepository
                .findValidToken(token, LocalDateTime.now())
                .orElseThrow(() -> {
                    log.warn("Invalid or expired email verification token");
                    return new InvalidTokenException("Invalid or expired verification token");
                });

        // Get user
        User user = verificationToken.getUser();

        if (user.getEmailVerified()) {
            log.warn("Email already verified for user: {}", user.getId());
            throw new IllegalArgumentException("Email already verified");
        }

        // Mark token as used
        verificationToken.setUsed(true);
        verificationToken.setVerifiedAt(LocalDateTime.now());
        emailVerificationTokenRepository.save(verificationToken);

        // Mark email as verified
        user.setEmailVerified(true);
        user.setEmailVerifiedAt(LocalDateTime.now());
        userRepository.save(user);

        log.info("Email verified for user: {}", user.getId());

        // Send welcome email
        try {
            emailService.sendWelcomeEmail(
                    user.getEmail(),
                    user.getFirstName() != null ? user.getFirstName() : user.getUsername()
            );
        } catch (Exception e) {
            log.error("Failed to send welcome email", e);
        }
    }

    /**
     * Resend email verification
     */
    @Transactional
    public void resendVerificationEmail(String email) {
        log.info("Resend verification email request for: {}", email);

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        if (user.getEmailVerified()) {
            throw new IllegalArgumentException("Email is already verified");
        }

        // Invalidate old tokens
        emailVerificationTokenRepository.findValidTokensForUser(user.getId(), LocalDateTime.now())
                .forEach(t -> {
                    t.setUsed(true);
                    emailVerificationTokenRepository.save(t);
                });

        // Create new token
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

        // Send email
        String verificationLink = frontendUrl + "/verify-email?token=" + verificationToken;
        emailService.sendEmailVerificationLink(
                user.getEmail(),
                user.getFirstName() != null ? user.getFirstName() : user.getUsername(),
                verificationLink
        );

        log.info("Verification email resent to: {}", email);
    }

    /**
     * Request password reset
     */
    @Transactional
    public void requestPasswordReset(String email) {
        log.info("Password reset request for: {}", email);

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        // Generate reset token
        String resetToken = tokenGenerationUtil.generateSecureToken();
        LocalDateTime expiryDate = LocalDateTime.now().plus(passwordResetExpiryHours, ChronoUnit.HOURS);

        PasswordResetToken token = PasswordResetToken.builder()
                .token(resetToken)
                .user(user)
                .expiryDate(expiryDate)
                .used(false)
                .createdAt(LocalDateTime.now())
                .build();

        passwordResetTokenRepository.save(token);
        log.debug("Password reset token created for user: {}", user.getId());

        // Send reset email
        String resetLink = frontendUrl + "/reset-password?token=" + resetToken;
        try {
            emailService.sendPasswordResetLink(
                    user.getEmail(),
                    user.getFirstName() != null ? user.getFirstName() : user.getUsername(),
                    resetLink
            );
            log.info("Password reset email sent to: {}", email);
        } catch (Exception e) {
            log.error("Failed to send password reset email", e);
            throw new RuntimeException("Failed to send reset email", e);
        }
    }

    /**
     * Reset password with token
     */
    @Transactional
    public void resetPassword(String token, String newPassword) {
        log.info("Password reset request with token");

        // Find valid token
        PasswordResetToken resetToken = passwordResetTokenRepository
                .findValidToken(token, LocalDateTime.now())
                .orElseThrow(() -> {
                    log.warn("Invalid or expired password reset token");
                    return new InvalidTokenException("Invalid or expired reset token");
                });

        User user = resetToken.getUser();

        // Validate new password
        if (newPassword == null || newPassword.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters");
        }

        // Update password
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setLastPasswordChangeAt(LocalDateTime.now());
        user.setTokenVersion(user.getTokenVersion() + 1); // Invalidate old tokens
        userRepository.save(user);

        // Mark reset token as used
        resetToken.setUsed(true);
        resetToken.setUsedAt(LocalDateTime.now());
        passwordResetTokenRepository.save(resetToken);

        log.info("Password reset successfully for user: {}", user.getId());
    }

    /**
     * Updated login to check email verification
     */
    public TokenResponseDto login(LoginRequestDto request) {
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
        if (!user.getEmailVerified()) {
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
            log.warn("Failed login attempt for user: {}", request.getUsername());
            throw new InvalidTokenException("Invalid credentials");
        }

        // Update last login time
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);
        log.info("User logged in: {}", user.getUsername());

        return generateTokenResponse(user);
    }

    /**
     * Refresh access token using refresh token
     * Implements token rotation - invalidates old token
     */
    public TokenResponseDto refreshAccessToken(TokenRefreshRequestDto request) {
        String refreshToken = request.getRefreshToken();

        // Validate refresh token
        if (!jwtTokenProvider.validateToken(refreshToken)) {
            log.warn("Invalid refresh token used");
            throw new InvalidTokenException("Invalid or expired refresh token");
        }

        // Check that it's actually a refresh token
        if (!jwtTokenProvider.isRefreshToken(refreshToken)) {
            log.warn("Access token used instead of refresh token");
            throw new InvalidTokenException("Invalid token type - refresh token expected");
        }

        // Extract user ID from token
        UUID userId = jwtTokenProvider.getUserIdFromToken(refreshToken);
        String username = jwtTokenProvider.getUsernameFromToken(refreshToken);

        // Find user in database
        User user = userRepository.findById(userId)
                .orElseThrow(() -> {
                    log.warn("Refresh token used for non-existent user: {}", userId);
                    return new UserNotFoundException("User not found");
                });

        // Verify user is still active
        if (user.getStatus() != UserStatus.ACTIVE) {
            log.warn("Refresh attempt by inactive user: {}", username);
            throw new InvalidTokenException("User account is not active");
        }

        // Token rotation: verify tokenVersion matches
        // If password changed, tokenVersion incremented, invalidating old tokens
        Claims claims = jwtTokenProvider.getClaimsFromToken(refreshToken);
        Integer tokenVersion = ((Number) claims.get("tokenVersion")).intValue();

        if (!tokenVersion.equals(user.getTokenVersion())) {
            log.warn("Token rotation detected - old token used after password change: {}", username);
            throw new InvalidTokenException("Token has been invalidated - please login again");
        }

        log.info("Access token refreshed for user: {}", username);

        // Generate NEW access token (new refresh token optional - can implement true rotation)
        return generateTokenResponse(user);
    }

    /**
     * Logout user
     * In token-based auth, we just invalidate on client side
     * But we can track it server-side if needed
     */
    @CacheEvict(value = "user", key = "#userId")
    public void logout(String userId) {
        log.info("User logged out: {}", userId);
        // Cache is cleared, forcing re-fetch on next request
    }

    /**
     * Invalidate all tokens for a user (password change, security incident)
     * Increments tokenVersion - all old tokens become invalid
     */
    @CacheEvict(value = "user", key = "#userId")
    public void invalidateAllTokens(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        user.setTokenVersion(user.getTokenVersion() + 1);
        user.setLastPasswordChangeAt(LocalDateTime.now());
        userRepository.save(user);

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
     * established.
     * <p>
     * The three sign-in paths - password login, refresh-token rotation and Google
     * sign-in - all route through here, so a session opened by OAuth is byte-identical
     * to one opened with a password from the JWT filter's point of view.
     */
    public TokenResponseDto issueTokens(User user) {
        return generateTokenResponse(user);
    }

    /**
     * Generate token response (both access & refresh tokens)
     * Private helper method
     */
    private TokenResponseDto generateTokenResponse(User user) {
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getUsername(), user.getEmail());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), user.getUsername(), user.getTokenVersion());

        long expiresIn = jwtTokenProvider.getTokenTimeToLive(accessToken);

        return TokenResponseDto.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(expiresIn)
                .userId(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .build();
    }

}
