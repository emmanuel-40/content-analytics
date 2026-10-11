package com.contentanalytics.security;

import com.contentanalytics.dto.TokenResponseDto;
import com.contentanalytics.entity.User;
import com.contentanalytics.repository.UserRepository;
import com.contentanalytics.service.AuthService;
import com.contentanalytics.util.CookieUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.UUID;

/**
 * Hands a successful Google handshake over to the existing JWT session.
 * <p>
 * The browser arrives here as a top-level navigation from Google, so the only useful
 * response is a redirect back to the SPA with cookies attached - there is no request
 * body to put tokens in. We mint the exact same access + refresh pair that
 * {@code POST /api/auth/login} produces, store them in the same HttpOnly cookies, and
 * bounce to {@code <frontend>/auth/callback} so the SPA can settle its own state.
 * <p>
 * Nothing about this class is Google-specific beyond the attribute keys; a second
 * provider would reuse it unchanged.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final AuthService authService;
    private final CookieUtil cookieUtil;
    private final UserRepository userRepository;

    /** Must end at the SPA route that completes the handshake - see docs/AUTHENTICATION.md. */
    @Value("${app.frontend.url:http://localhost:5173}")
    private String frontendUrl;

    /** Access-token cookie lifetime; mirrors {@code app.jwt.expiration}. */
    @Value("${app.jwt.expiration}")
    private long accessTokenTtlMs;

    /** Refresh-token cookie lifetime; mirrors {@code app.jwt.refresh.expiration}. */
    @Value("${app.jwt.refresh.expiration}")
    private long refreshTokenTtlMs;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {

        if (!(authentication instanceof OAuth2AuthenticationToken oauthAuthentication)) {
            log.error("OAuth2 success handler reached with unexpected authentication type: {}",
                    authentication == null ? "null" : authentication.getClass().getName());
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Unexpected authentication type");
            return;
        }

        OAuth2User principal = oauthAuthentication.getPrincipal();
        String userId = principal.getAttribute(GoogleOAuth2UserService.ATTR_USER_ID);

        if (userId == null) {
            log.error("OAuth2 principal carries no local user id");
            response.sendRedirect(redirect("missing_user"));
            return;
        }

        User user = userRepository.findById(UUID.fromString(userId))
                .orElseThrow(() -> new IllegalStateException("OAuth user " + userId + " vanished mid-handshake"));

        TokenResponseDto tokens = authService.issueTokens(user);

        cookieUtil.setAccessTokenCookie(response, tokens.getAccessToken(), toSeconds(accessTokenTtlMs));
        cookieUtil.setRefreshTokenCookie(response, tokens.getRefreshToken(), toSeconds(refreshTokenTtlMs));

        log.info("Google sign-in succeeded for user '{}' ({})", user.getUsername(), user.getId());
        response.sendRedirect(redirect("success"));
    }

    private String redirect(String status) {
        String base = frontendUrl.endsWith("/")
                ? frontendUrl.substring(0, frontendUrl.length() - 1)
                : frontendUrl;
        return UriComponentsBuilder.fromUriString(base)
                .path("/auth/callback")
                .queryParam("status", status)
                .build(true)
                .toUriString();
    }

    private long toSeconds(long millis) {
        return Math.max(1, millis / 1000);
    }
}
