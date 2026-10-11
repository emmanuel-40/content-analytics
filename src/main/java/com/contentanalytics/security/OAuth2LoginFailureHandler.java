package com.contentanalytics.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

/**
 * Sends a failed Google handshake back to the SPA with a machine-readable reason.
 * <p>
 * The reason is drawn from a fixed vocabulary rather than copying the exception
 * message, so nothing a remote party controls can ever be reflected into the
 * redirect URL (no open redirect, no HTML/JS injection into the landing page).
 * The detail stays in the server log, where it belongs.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OAuth2LoginFailureHandler implements AuthenticationFailureHandler {

    private static final String REASON_ACCESS_DENIED = "access_denied";
    private static final String REASON_LINK_CONFLICT = "link_conflict";
    private static final String REASON_EMAIL_UNVERIFIED = "email_unverified";
    private static final String REASON_STATE_MISMATCH = "state_mismatch";
    private static final String REASON_MISSING_CLIENT = "client_not_configured";
    private static final String REASON_UNKNOWN = "error";

    @Value("${app.frontend.url:http://localhost:5173}")
    private String frontendUrl;

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {

        String reason = classify(exception);
        log.warn("Google sign-in failed ({}): {}", reason, exception.getMessage());

        String base = frontendUrl.endsWith("/")
                ? frontendUrl.substring(0, frontendUrl.length() - 1)
                : frontendUrl;

        String target = UriComponentsBuilder.fromUriString(base)
                .path("/auth/callback")
                .queryParam("status", "error")
                .queryParam("reason", reason)
                .build(true)
                .toUriString();

        response.sendRedirect(target);
    }

    /**
     * Collapse Spring Security's error codes into the small set the frontend is
     * allowed to render. Everything unmapped degrades to a generic {@code error}.
     */
    private String classify(AuthenticationException exception) {
        if (!(exception instanceof OAuth2AuthenticationException oauthException)) {
            return REASON_UNKNOWN;
        }

        String code = oauthException.getError() == null
                ? ""
                : oauthException.getError().getErrorCode();

        return switch (code) {
            case "access_denied" -> REASON_ACCESS_DENIED;
            case "account_link_conflict" -> REASON_LINK_CONFLICT;
            case "invalid_email", "missing_claim" -> REASON_EMAIL_UNVERIFIED;
            // `invalid_request` on the callback is almost always a stale/missing state
            // cookie - the user left the tab open too long, or opened the callback in a
            // different browser than the one that started it.
            case "invalid_request", "invalid_state" -> REASON_STATE_MISMATCH;
            // Unconfigured client id (the `local-dev-not-configured` placeholder) or a
            // missing ClientRegistration bean.
            case "invalid_client", "unauthorized_client" -> REASON_MISSING_CLIENT;
            default -> REASON_UNKNOWN;
        };
    }
}
