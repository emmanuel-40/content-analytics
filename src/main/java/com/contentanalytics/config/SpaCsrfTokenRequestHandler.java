package com.contentanalytics.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;

import java.util.function.Supplier;

/**
 * CSRF handler for a cookie-authenticated single-page application.
 * <p>
 * Delegates entirely to {@link XorCsrfTokenRequestAttributeHandler}, which is what makes the
 * BREACH mitigation work: the token is XOR-masked with fresh random bytes before it is handed
 * to the client, so it cannot be recovered from a compression side-channel.
 * <p>
 * Because the value the client must send back is the <em>masked</em> token - not the raw value
 * stored in the cookie - the SPA must fetch it from {@code GET /api/auth/csrf} and echo that
 * in the {@code X-XSRF-TOKEN} header. Reading {@code document.cookie} alone is not sufficient.
 * <p>
 * This class exists to make that requirement explicit at the configuration site rather than
 * leaving a bare XOR handler for someone to "fix" later by reverting to the plain handler,
 * which silently removes the BREACH protection.
 */
public final class SpaCsrfTokenRequestHandler extends CsrfTokenRequestAttributeHandler {

    private final CsrfTokenRequestAttributeHandler delegate =
            new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       Supplier<CsrfToken> deferredCsrfToken) {
        this.delegate.handle(request, response, deferredCsrfToken);
    }

    /**
     * Un-masks the submitted value so {@code CsrfFilter} can compare it against the raw token.
     */
    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        return this.delegate.resolveCsrfTokenValue(request, csrfToken);
    }

}