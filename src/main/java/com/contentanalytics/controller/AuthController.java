package com.contentanalytics.controller;

import com.contentanalytics.dto.*;
import com.contentanalytics.entity.User;
import com.contentanalytics.exception.InvalidTokenException;
import com.contentanalytics.exception.UserAlreadyExistsException;
import com.contentanalytics.exception.UserNotFoundException;
import com.contentanalytics.service.AuthService;
import com.contentanalytics.util.CookieUtil;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.time.LocalDateTime;
import java.util.Map;
import com.contentanalytics.repository.EmailVerificationTokenRepository;
import com.contentanalytics.repository.PasswordResetTokenRepository;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;
    private final CookieUtil cookieUtil;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    @Value("${jwt.refresh-expiration-days:7}")
    private int refreshExpirationDays;

    /**
     * POST /api/auth/register
     * Register a new user
     */
    @PostMapping("/register")
    public ResponseEntity<ApiResponse> register(
            @Valid @RequestBody RegisterRequestDto request) {

        log.info("Register request for user: {}", request.getUsername());

        try {
            authService.register(request);  // ← Returns void now

            log.info("User registered successfully: {}", request.getUsername());

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.builder()
                            .status(HttpStatus.CREATED.value())
                            .message("Registration successful! Check your email to verify your account before logging in.")
                            .build());

        } catch (UserAlreadyExistsException e) {
            log.warn("Registration failed - user already exists: {}", request.getUsername());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.builder()
                            .status(HttpStatus.CONFLICT.value())
                            .message(e.getMessage())
                            .build());

        } catch (IllegalArgumentException e) {
            log.warn("Registration validation error: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.builder()
                            .status(HttpStatus.BAD_REQUEST.value())
                            .message(e.getMessage())
                            .build());

        } catch (Exception e) {
            log.error("Unexpected error during registration", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.builder()
                            .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                            .message("Registration failed")
                            .build());
        }
    }

    /**
     * POST /api/auth/login
     * Authenticate user and return tokens
     */
    @PostMapping("/login")
    public ResponseEntity<TokenResponseDto> login(
            @Valid @RequestBody LoginRequestDto request,
            HttpServletResponse response) {

        log.info("Login request for user: {}", request.getUsername());

        try {
            TokenResponseDto tokenResponse = authService.login(request);

            // Tokens are delivered via HTTP-only cookies so browser JavaScript (and therefore
            // any injected script) cannot read them. Clients that cannot use cookies (curl,
            // native mobile apps) may still read them from the response body.
            long refreshTokenExpirationSeconds = (long) refreshExpirationDays * 24 * 60 * 60;
            cookieUtil.setRefreshTokenCookie(response, tokenResponse.getRefreshToken(),
                    refreshTokenExpirationSeconds);
            cookieUtil.setAccessTokenCookie(response, tokenResponse.getAccessToken(),
                    tokenResponse.getExpiresIn());

            return ResponseEntity.ok(tokenResponse);

        } catch (UserNotFoundException | IllegalArgumentException e) {
            log.warn("Login failed: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error during login", e);
            throw e;
        }
    }

    /**
     * POST /api/auth/verify-email
     * Verify user's email with verification token
     *
     * Request body:
     * {
     *   "token": "eyJhbGciOiJIUzUxMiJ9..."
     * }
     */
    @PostMapping("/verify-email")
    public ResponseEntity<ApiResponse> verifyEmail(
            @Valid @RequestBody VerifyEmailRequestDto request) {

        log.info("Email verification request");

        try {
            authService.verifyEmail(request.getToken());

            ApiResponse response = ApiResponse.builder()
                    .status(HttpStatus.OK.value())
                    .message("Email verified successfully. You can now login.")
                    .build();

            return ResponseEntity.ok(response);

        } catch (InvalidTokenException e) {
            log.warn("Email verification failed - invalid token");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.builder()
                            .status(HttpStatus.BAD_REQUEST.value())
                            .message(e.getMessage())
                            .build());

        } catch (IllegalArgumentException e) {
            log.warn("Email verification failed - {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.builder()
                            .status(HttpStatus.CONFLICT.value())
                            .message(e.getMessage())
                            .build());

        } catch (Exception e) {
            log.error("Unexpected error during email verification", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.builder()
                            .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                            .message("Email verification failed")
                            .build());
        }
    }


    @GetMapping("/verify-email")
    public ResponseEntity<ApiResponse> verifyEmail(
            @RequestParam("token") String token) {

        log.info("Email verification request with token");

        try {
            authService.verifyEmail(token);

            ApiResponse response = ApiResponse.builder()
                    .status(HttpStatus.OK.value())
                    .message("Email verified successfully. You can now login.")
                    .build();

            return ResponseEntity.ok(response);

        } catch (InvalidTokenException e) {
            log.warn("Email verification failed - invalid token");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.builder()
                            .status(HttpStatus.BAD_REQUEST.value())
                            .message("Invalid or expired verification token")
                            .build());

        } catch (IllegalArgumentException e) {
            log.warn("Email verification failed - {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.builder()
                            .status(HttpStatus.CONFLICT.value())
                            .message(e.getMessage())
                            .build());

        } catch (Exception e) {
            log.error("Unexpected error during email verification", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.builder()
                            .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                            .message("Email verification failed")
                            .build());
        }
    }


    /**
     * POST /api/auth/resend-verification-email
     * Resend verification email to user
     */
    @PostMapping("/resend-verification-email")
    public ResponseEntity<ApiResponse> resendVerificationEmail(
            @Valid @RequestBody ResendVerificationEmailRequestDto request) {

        log.info("Resend verification email request for: {}", request.getEmail());

        try {
            authService.resendVerificationEmail(request.getEmail());

            ApiResponse response = ApiResponse.builder()
                    .status(HttpStatus.OK.value())
                    .message("Verification email sent. Please check your inbox.")
                    .build();

            return ResponseEntity.ok(response);

        } catch (UserNotFoundException e) {
            // Security: Don't reveal if email exists
            log.warn("Resend verification email for non-existent email: {}", request.getEmail());
            ApiResponse response = ApiResponse.builder()
                    .status(HttpStatus.OK.value())
                    .message("If email exists in our system, verification email will be sent.")
                    .build();
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            log.warn("Resend verification failed - {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.builder()
                            .status(HttpStatus.CONFLICT.value())
                            .message(e.getMessage())
                            .build());

        } catch (Exception e) {
            log.error("Error during resend verification email", e);
            // Still return generic message for security
            ApiResponse response = ApiResponse.builder()
                    .status(HttpStatus.OK.value())
                    .message("If email exists in our system, verification email will be sent.")
                    .build();
            return ResponseEntity.ok(response);
        }
    }

    /**
     * POST /api/auth/forgot-password
     * Request password reset link
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequestDto request) {

        log.info("Forgot password request for: {}", request.getEmail());

        try {
            authService.requestPasswordReset(request.getEmail());

            // Always return success (don't reveal if email exists)
            ApiResponse response = ApiResponse.builder()
                    .status(HttpStatus.OK.value())
                    .message("If an account exists with that email, a password reset link will be sent.")
                    .build();

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error processing forgot password request", e);

            // Still return generic message for security
            ApiResponse response = ApiResponse.builder()
                    .status(HttpStatus.OK.value())
                    .message("If an account exists with that email, a password reset link will be sent.")
                    .build();

            return ResponseEntity.ok(response);
        }
    }

    /**
     * POST /api/auth/reset-password
     * Reset password with valid reset token
     */
    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse> resetPassword(
            @Valid @RequestBody ResetPasswordRequestDto request) {

        log.info("Password reset request");

        try {
            authService.resetPassword(request.getToken(), request.getNewPassword());

            ApiResponse response = ApiResponse.builder()
                    .status(HttpStatus.OK.value())
                    .message("Password reset successfully. You can now login with your new password.")
                    .build();

            return ResponseEntity.ok(response);

        } catch (InvalidTokenException e) {
            log.warn("Password reset failed - invalid token");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.builder()
                            .status(HttpStatus.BAD_REQUEST.value())
                            .message(e.getMessage())
                            .build());

        } catch (IllegalArgumentException e) {
            log.warn("Password reset failed - {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.builder()
                            .status(HttpStatus.BAD_REQUEST.value())
                            .message(e.getMessage())
                            .build());

        } catch (Exception e) {
            log.error("Unexpected error during password reset", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.builder()
                            .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                            .message("Password reset failed")
                            .build());
        }
    }

    /**
     * GET /api/auth/verify-token/{token}
     * Check if a reset/verification token is valid
     */
    @GetMapping("/verify-token/{token}")
    public ResponseEntity<ApiResponse> verifyToken(
            @PathVariable String token) {

        log.debug("Token validation request");

        try {
            // Try as verification token first
            var verificationToken = emailVerificationTokenRepository
                    .findValidToken(token, LocalDateTime.now());

            if (verificationToken.isPresent()) {
                ApiResponse response = ApiResponse.builder()
                        .status(HttpStatus.OK.value())
                        .message("Valid verification token")
                        .data(Map.of("type", "email_verification"))
                        .build();
                return ResponseEntity.ok(response);
            }

            // Try as password reset token
            var resetToken = passwordResetTokenRepository
                    .findValidToken(token, LocalDateTime.now());

            if (resetToken.isPresent()) {
                ApiResponse response = ApiResponse.builder()
                        .status(HttpStatus.OK.value())
                        .message("Valid password reset token")
                        .data(Map.of("type", "password_reset"))
                        .build();
                return ResponseEntity.ok(response);
            }

            // Token not found or expired
            return ResponseEntity.badRequest()
                    .body(ApiResponse.builder()
                            .status(HttpStatus.BAD_REQUEST.value())
                            .message("Invalid or expired token")
                            .build());

        } catch (Exception e) {
            log.error("Error verifying token", e);
            return ResponseEntity.badRequest()
                    .body(ApiResponse.builder()
                            .status(HttpStatus.BAD_REQUEST.value())
                            .message("Invalid or expired token")
                            .build());
        }
    }

    /**
     * POST /api/auth/refresh
     * Refresh expired access token using refresh token
     */
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse> refreshToken(
            @RequestBody(required = false) TokenRefreshRequestDto request,
            @CookieValue(value = "refreshToken", required = false) String cookieRefreshToken,
            HttpServletResponse response) {

        log.info("Token refresh request");

        try {
            // Get refresh token from request body or cookie
            String refreshToken = null;
            if (request != null && request.getRefreshToken() != null) {
                refreshToken = request.getRefreshToken();
            } else if (cookieRefreshToken != null) {
                refreshToken = cookieRefreshToken;
            }

            if (refreshToken == null) {
                log.warn("Refresh token not found in request or cookie");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(ApiResponse.builder()
                                .status(HttpStatus.UNAUTHORIZED.value())
                                .message("Refresh token not found")
                                .build());
            }

            // Refresh the access token
            TokenResponseDto tokenResponse = authService.refreshAccessToken(
                    new TokenRefreshRequestDto(refreshToken));

            // Rotate both cookies so the browser stays in sync with the new tokens
            long refreshTokenExpirationSeconds = (long) refreshExpirationDays * 24 * 60 * 60;
            cookieUtil.setRefreshTokenCookie(response, tokenResponse.getRefreshToken(),
                    refreshTokenExpirationSeconds);
            cookieUtil.setAccessTokenCookie(response, tokenResponse.getAccessToken(),
                    tokenResponse.getExpiresIn());

            return ResponseEntity.ok(ApiResponse.builder()
                    .status(HttpStatus.OK.value())
                    .message("Token refreshed successfully")
                    .data(tokenResponse)
                    .build());

        } catch (InvalidTokenException e) {
            log.warn("Token refresh failed - invalid token");
            cookieUtil.clearRefreshTokenCookie(response);
            cookieUtil.clearAccessTokenCookie(response);

            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.builder()
                            .status(HttpStatus.UNAUTHORIZED.value())
                            .message("Invalid or expired refresh token")
                            .build());

        } catch (Exception e) {
            log.error("Unexpected error during token refresh", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.builder()
                            .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                            .message("Token refresh failed")
                            .build());
        }
    }

    /**
     * POST /api/auth/logout
     * Logout the authenticated user
     */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse> logout(HttpServletResponse response) {

        log.info("Logout request");

        try {
            // Get authenticated user
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

            if (authentication != null && authentication.isAuthenticated()) {
                String username = authentication.getName();
                log.info("Logout for user: {}", username);
            }

            // Clear both token cookies
            cookieUtil.clearRefreshTokenCookie(response);
            cookieUtil.clearAccessTokenCookie(response);

            // Clear security context on server side
            SecurityContextHolder.clearContext();

            // Return success response
            ApiResponse apiResponse = ApiResponse.builder()
                    .status(HttpStatus.OK.value())
                    .message("Logged out successfully")
                    .build();

            return ResponseEntity.ok(apiResponse);

        } catch (Exception e) {
            log.error("Error during logout", e);

            // Still clear cookies and context
            cookieUtil.clearRefreshTokenCookie(response);
            cookieUtil.clearAccessTokenCookie(response);
            SecurityContextHolder.clearContext();

            return ResponseEntity.ok(ApiResponse.builder()
                    .status(HttpStatus.OK.value())
                    .message("Logged out successfully")
                    .build());
        }
    }

    /**
     * GET /api/auth/me
     * Get current authenticated user details
     */
    @GetMapping("/me")
    public ResponseEntity<CurrentUserDto> getCurrentUser() {

        log.debug("Get current user request");

        try {
            // Get authenticated user from security context
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

            if (authentication == null || !authentication.isAuthenticated()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }

            String username = authentication.getName();
            log.debug("Fetching current user details: {}", username);

            // Get user details from service
            User user = authService.getCurrentUser(username);

            CurrentUserDto userDto = CurrentUserDto.builder()
                    .id(user.getId())
                    .username(user.getUsername())
                    .email(user.getEmail())
                    .firstName(user.getFirstName())
                    .lastName(user.getLastName())
                    .role(user.getRole().name())
                    .status(user.getStatus().name())
                    .emailVerified(user.getEmailVerified())
                    .createdAt(user.getCreatedAt())
                    .lastLoginAt(user.getLastLoginAt())
                    .build();

            return ResponseEntity.ok(userDto);

        } catch (Exception e) {
            log.error("Error retrieving current user", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * POST /api/auth/invalidate-tokens
     * Invalidate all tokens for current user
     */
    @PostMapping("/invalidate-tokens")
    public ResponseEntity<ApiResponse> invalidateAllTokens(HttpServletResponse response) {

        log.info("Invalidate all tokens request");

        try {
            // Get authenticated user
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

            if (authentication == null || !authentication.isAuthenticated()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }

            String username = authentication.getName();
            log.info("Invalidating all tokens for user: {}", username);

            // Get user details and invalidate tokens
            User user = authService.getCurrentUser(username);
            authService.invalidateAllTokens(user.getId());

            // Clear refresh token cookie
            cookieUtil.clearRefreshTokenCookie(response);

            // Clear security context
            SecurityContextHolder.clearContext();

            ApiResponse apiResponse = ApiResponse.builder()
                    .status(HttpStatus.OK.value())
                    .message("All tokens have been invalidated. Please login again.")
                    .build();

            return ResponseEntity.ok(apiResponse);

        } catch (Exception e) {
            log.error("Error invalidating tokens", e);
            cookieUtil.clearRefreshTokenCookie(response);
            SecurityContextHolder.clearContext();

            return ResponseEntity.ok(ApiResponse.builder()
                    .status(HttpStatus.OK.value())
                    .message("All tokens have been invalidated. Please login again.")
                    .build());
        }
    }

    /**
     * GET /api/auth/csrf
     * Returns the current CSRF token for the browser SPA.
     * <p>
     * The response also refreshes the {@code XSRF-TOKEN} cookie. The SPA should send the value
     * returned here in the {@code X-XSRF-TOKEN} header on every state-changing request.
     */
    @GetMapping("/csrf")
    public ResponseEntity<Map<String, String>> csrf(
            @CookieValue(value = "XSRF-TOKEN", required = false) String cookieToken) {

        // Spring Security exposes the (masked) token as a request attribute
        // under its class name.
        Object attribute = RequestContextHolder.currentRequestAttributes()
                .getAttribute(CsrfToken.class.getName(), RequestAttributes.SCOPE_REQUEST);

        CsrfToken token = attribute instanceof CsrfToken csrf ? csrf : null;

        String value = token != null ? token.getToken() : cookieToken;

        return ResponseEntity.ok(Map.of(
                "token", value != null ? value : "",
                "headerName", "X-XSRF-TOKEN"
        ));
    }

    /**
     * GET /api/auth/validate-token
     * Validate if current access token is still valid
     */
    @GetMapping("/validate-token")
    public ResponseEntity<TokenValidationDto> validateToken() {

        log.debug("Token validation request");

        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

            boolean isValid = authentication != null && authentication.isAuthenticated();

            TokenValidationDto validationDto = TokenValidationDto.builder()
                    .valid(isValid)
                    .username(isValid ? authentication.getName() : null)
                    .build();

            return ResponseEntity.ok(validationDto);

        } catch (Exception e) {
            log.error("Error validating token", e);
            return ResponseEntity.ok(TokenValidationDto.builder()
                    .valid(false)
                    .build());
        }
    }

}