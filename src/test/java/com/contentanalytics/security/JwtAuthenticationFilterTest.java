package com.contentanalytics.security;

import com.contentanalytics.entity.User;
import com.contentanalytics.entity.UserRole;
import com.contentanalytics.entity.UserStatus;
import com.contentanalytics.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("JwtAuthenticationFilter session checks")
class JwtAuthenticationFilterTest {

    private static final String SECRET =
            "test-secret-key-that-is-definitely-long-enough-for-hmac-sha512-signing-algorithm";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-15T09:30:00Z"), ZoneOffset.UTC);
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock private UserRepository userRepository;

    private JwtTokenProvider provider;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider(CLOCK);
        ReflectionTestUtils.setField(provider, "jwtSecret", SECRET);
        ReflectionTestUtils.setField(provider, "jwtExpirationMs", 900_000L);
        ReflectionTestUtils.setField(provider, "refreshTokenExpirationMs", 604_800_000L);
        filter = new JwtAuthenticationFilter(provider, new TokenSessionValidator(userRepository, provider));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a current access token for an ACTIVE user authenticates")
    void currentTokenAuthenticates() throws Exception {
        String token = provider.generateAccessToken(USER_ID, "oliver", "o@example.com", 3);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(UserStatus.ACTIVE, 3)));

        filter.doFilter(requestWithBearer(token), new MockHttpServletResponse(), new MockFilterChain());

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getName()).isEqualTo("oliver");
        assertThat(authentication.getDetails()).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) authentication.getDetails()).get("userId")).isEqualTo(USER_ID.toString());
    }

    @Test
    @DisplayName("a token with a stale version never authenticates")
    void staleTokenIsRejected() throws Exception {
        String token = provider.generateAccessToken(USER_ID, "oliver", "o@example.com", 3);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(UserStatus.ACTIVE, 4)));

        filter.doFilter(requestWithBearer(token), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("a token for a banned user never authenticates")
    void inactiveUserIsRejected() throws Exception {
        String token = provider.generateAccessToken(USER_ID, "oliver", "o@example.com", 3);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(UserStatus.BANNED, 3)));

        filter.doFilter(requestWithBearer(token), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("a legacy token without a version claim is rejected")
    void tokenWithoutVersionIsRejected() throws Exception {
        String token = provider.generateAccessToken(USER_ID, "oliver", "o@example.com");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(UserStatus.ACTIVE, 1)));

        filter.doFilter(requestWithBearer(token), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("a refresh token presented as a bearer token cannot authenticate")
    void refreshTokenIsRejected() throws Exception {
        String token = provider.generateRefreshToken(USER_ID, "oliver", 1);

        filter.doFilter(requestWithBearer(token), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("requests without a token do not touch the database")
    void anonymousRequestDoesNotQueryUsers() throws Exception {
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(userRepository);
    }

    private MockHttpServletRequest requestWithBearer(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return request;
    }

    private User user(UserStatus status, int tokenVersion) {
        return User.builder()
                .id(USER_ID)
                .username("oliver")
                .email("o@example.com")
                .role(UserRole.USER)
                .status(status)
                .tokenVersion(tokenVersion)
                .emailVerified(true)
                .provider(com.contentanalytics.entity.AuthProvider.LOCAL)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}
