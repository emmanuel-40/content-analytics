package com.contentanalytics.service;

import com.contentanalytics.dto.LoginRequestDto;
import com.contentanalytics.dto.TokenRefreshRequestDto;
import com.contentanalytics.dto.TokenResponseDto;
import com.contentanalytics.entity.*;
import com.contentanalytics.exception.InvalidTokenException;
import com.contentanalytics.repository.EmailVerificationTokenRepository;
import com.contentanalytics.repository.PasswordResetTokenRepository;
import com.contentanalytics.repository.RefreshTokenRepository;
import com.contentanalytics.repository.UserRepository;
import com.contentanalytics.security.JwtTokenProvider;
import com.contentanalytics.util.TokenGenerationUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the token lifecycle in {@link AuthService}. Repositories are
 * mocked; the JWT provider is real (with a pinned clock) so the hashes and
 * claims under test are the ones production code actually produces.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService token lifecycle")
class AuthServiceTokenTest {

    private static final String SECRET =
            "test-secret-key-that-is-definitely-long-enough-for-hmac-sha512-signing-algorithm";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-15T09:30:00Z"), ZoneOffset.UTC);
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID FAMILY_ID = UUID.randomUUID();

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private LoginAttemptService loginAttemptService;
    @Mock private EmailService emailService;
    @Mock private EmailVerificationTokenRepository emailVerificationTokenRepository;
    @Mock private PasswordResetTokenRepository passwordResetTokenRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private RefreshTokenRevocationService refreshTokenRevocationService;
    @Mock private TokenGenerationUtil tokenGenerationUtil;

    private AuthService authService;

    private JwtTokenProvider provider;
    private User user;

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider(CLOCK);
        ReflectionTestUtils.setField(provider, "jwtSecret", SECRET);
        ReflectionTestUtils.setField(provider, "jwtExpirationMs", 900_000L);
        ReflectionTestUtils.setField(provider, "refreshTokenExpirationMs", 604_800_000L);

        authService = new AuthService(userRepository, provider, passwordEncoder, loginAttemptService,
                emailService, emailVerificationTokenRepository, passwordResetTokenRepository,
                refreshTokenRepository, refreshTokenRevocationService, tokenGenerationUtil);
        ReflectionTestUtils.setField(authService, "refreshTokenExpirationMs", 604_800_000L);
        ReflectionTestUtils.setField(authService, "frontendUrl", "http://localhost:5173");
        ReflectionTestUtils.setField(authService, "emailVerificationExpiryHours", 24);
        ReflectionTestUtils.setField(authService, "passwordResetExpiryHours", 1);

        user = User.builder()
                .id(USER_ID)
                .username("oliver")
                .email("oliver@example.com")
                .password("hashed-password")
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .tokenVersion(1)
                .emailVerified(true)
                .provider(AuthProvider.LOCAL)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("refresh rotates the token, keeps the family, and persists only the hash")
    void refreshRotatesToken() {
        String raw = provider.generateRefreshToken(USER_ID, "oliver", 1);
        RefreshToken stored = stored(raw, RefreshTokenStatus.ACTIVE, false, false);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(refreshTokenRepository.findByTokenHash(hash(raw))).thenReturn(Optional.of(stored));
        when(refreshTokenRepository.claimToken(
                eq(hash(raw)), any(), any(), eq(RefreshTokenStatus.ACTIVE), eq(RefreshTokenStatus.USED)))
                .thenReturn(1);
        when(refreshTokenRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TokenResponseDto result = authService.refreshAccessToken(new TokenRefreshRequestDto(raw));

        assertThat(result.getRefreshToken()).isNotEqualTo(raw);
        assertThat(result.getAccessToken()).isNotBlank();
        assertThat(provider.getUserIdFromToken(result.getRefreshToken())).isEqualTo(USER_ID);
        assertThat(provider.getTokenVersionFromToken(result.getRefreshToken())).isEqualTo(1);

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        RefreshToken replacement = captor.getValue();
        assertThat(replacement.getFamilyId()).isEqualTo(FAMILY_ID);
        assertThat(replacement.getTokenHash()).isEqualTo(hash(result.getRefreshToken()));
        assertThat(replacement.getTokenHash()).isNotEqualTo(result.getRefreshToken());
        assertThat(replacement.getReplacedBy()).isNotNull();
        assertThat(replacement.getStatus()).isEqualTo(RefreshTokenStatus.ACTIVE);
    }

    @Test
    @DisplayName("re-presenting a used token revokes its family and bumps the token version")
    void replayRevokesFamily() {
        String raw = provider.generateRefreshToken(USER_ID, "oliver", 1);
        RefreshToken used = stored(raw, RefreshTokenStatus.USED, true, false);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(refreshTokenRepository.findByTokenHash(hash(raw))).thenReturn(Optional.of(used));

        assertThatThrownBy(() -> authService.refreshAccessToken(new TokenRefreshRequestDto(raw)))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("invalidated");

        verify(refreshTokenRevocationService).revokeFamilyAndBumpVersion(USER_ID, FAMILY_ID);
        verify(refreshTokenRepository, never()).claimToken(anyString(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("losing the atomic claim race is treated as replay")
    void lostClaimRevokesFamily() {
        String raw = provider.generateRefreshToken(USER_ID, "oliver", 1);
        RefreshToken active = stored(raw, RefreshTokenStatus.ACTIVE, false, false);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(refreshTokenRepository.findByTokenHash(hash(raw))).thenReturn(Optional.of(active));
        when(refreshTokenRepository.claimToken(
                anyString(), any(), any(), eq(RefreshTokenStatus.ACTIVE), eq(RefreshTokenStatus.USED)))
                .thenReturn(0);

        assertThatThrownBy(() -> authService.refreshAccessToken(new TokenRefreshRequestDto(raw)))
                .isInstanceOf(InvalidTokenException.class);

        verify(refreshTokenRevocationService).revokeFamilyAndBumpVersion(USER_ID, FAMILY_ID);
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("a token minted before a token-version bump is rejected and its family revoked")
    void staleVersionIsRejected() {
        user.setTokenVersion(3);
        String raw = provider.generateRefreshToken(USER_ID, "oliver", 1);
        RefreshToken active = stored(raw, RefreshTokenStatus.ACTIVE, false, false);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(refreshTokenRepository.findByTokenHash(hash(raw))).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> authService.refreshAccessToken(new TokenRefreshRequestDto(raw)))
                .isInstanceOf(InvalidTokenException.class);

        verify(refreshTokenRevocationService).revokeFamilyAndBumpVersion(USER_ID, FAMILY_ID);
    }

    @Test
    @DisplayName("logout revokes the presented token, all sessions and the token version")
    void logoutRevokesSessions() {
        String raw = provider.generateRefreshToken(USER_ID, "oliver", 1);

        when(refreshTokenRepository.revokeByTokenHash(
                eq(hash(raw)), any(), eq(RefreshTokenStatus.REVOKED))).thenReturn(1);
        when(refreshTokenRepository.revokeAllForUser(eq(USER_ID), any(), eq(RefreshTokenStatus.REVOKED)))
                .thenReturn(2);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        authService.logout(USER_ID, raw);

        verify(refreshTokenRepository).revokeByTokenHash(eq(hash(raw)), any(), eq(RefreshTokenStatus.REVOKED));
        verify(refreshTokenRepository).revokeAllForUser(eq(USER_ID), any(), eq(RefreshTokenStatus.REVOKED));
        assertThat(user.getTokenVersion()).isEqualTo(2);
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("login creates a brand new refresh-token family")
    void loginStartsRefreshFamily() {
        when(loginAttemptService.isLocked("oliver")).thenReturn(false);
        when(userRepository.findByUsername("oliver")).thenReturn(Optional.of(user));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "hashed-password")).thenReturn(true);
        when(refreshTokenRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TokenResponseDto result = authService.login(new LoginRequestDto("oliver", "password123"));

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        assertThat(captor.getValue().getFamilyId()).isNotNull();
        assertThat(captor.getValue().getTokenHash()).isEqualTo(hash(result.getRefreshToken()));
        assertThat(provider.getTokenVersionFromToken(result.getAccessToken())).isEqualTo(1);
    }

    @Test
    @DisplayName("email verification claims the token once and queues the welcome email in the same transaction")
    void verificationIsAtomicAndMailIsQueued() {
        user.setEmailVerified(false);
        EmailVerificationToken verification = EmailVerificationToken.builder()
                .token("verify-123")
                .user(user)
                .expiryDate(LocalDateTime.now().plusHours(1))
                .used(false)
                .build();

        when(emailVerificationTokenRepository.findByToken("verify-123")).thenReturn(Optional.of(verification));
        when(emailVerificationTokenRepository.claimToken(eq("verify-123"), any())).thenReturn(1);

        authService.verifyEmail("verify-123");

        // The outbox row is part of the same transaction as the verification state.
        verify(emailService).sendWelcomeEmail("oliver@example.com", "oliver");
        assertThat(user.getEmailVerified()).isTrue();
        verify(emailVerificationTokenRepository).invalidateAllForUser(USER_ID);
    }

    @Test
    @DisplayName("a lost password-reset claim does not change the password")
    void resetPasswordClaimsAtomically() {
        PasswordResetToken reset = PasswordResetToken.builder()
                .token("reset-123")
                .user(user)
                .expiryDate(LocalDateTime.now().plusHours(1))
                .used(false)
                .build();

        when(passwordResetTokenRepository.findByToken("reset-123")).thenReturn(Optional.of(reset));
        when(passwordResetTokenRepository.claimToken(eq("reset-123"), any())).thenReturn(0);

        assertThatThrownBy(() -> authService.resetPassword("reset-123", "newPassword1"))
                .isInstanceOf(InvalidTokenException.class);

        verify(passwordResetTokenRepository).invalidateAllForUser(eq(USER_ID), any());
        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(anyString());
        assertThat(user.getTokenVersion()).isEqualTo(1);
    }

    private RefreshToken stored(String raw, RefreshTokenStatus status, boolean used, boolean revoked) {
        return RefreshToken.builder()
                .id(UUID.randomUUID())
                .user(user)
                .tokenHash(hash(raw))
                .familyId(FAMILY_ID)
                .status(status)
                .used(used)
                .revoked(revoked)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .build();
    }

    private String hash(String raw) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
