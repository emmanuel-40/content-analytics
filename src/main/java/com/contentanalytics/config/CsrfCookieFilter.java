package com.contentanalytics.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Forces the deferred CSRF token to be written to the {@code XSRF-TOKEN} cookie.
 * <p>
 * Spring Security defers loading the CSRF token until something actually needs it. In a
 * cookie-based API the browser never has a request that requires it early, so the cookie
 * would never be sent and every state-changing call from the SPA would fail with 403.
 * <p>
 * Reading the token here causes the repository to emit the cookie on the response, so the
 * frontend can read it and echo it back in the {@code X-XSRF-TOKEN} header.
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        CsrfToken csrfToken = csrfToken(request);

        // Materialising the token triggers the repository to set the XSRF-TOKEN cookie.
        if (csrfToken != null) {
            csrfToken.getToken();
        }

        filterChain.doFilter(request, response);
    }

    private CsrfToken csrfToken(HttpServletRequest request) {
        return (CsrfToken) request.getAttribute(CsrfToken.class.getName());
    }

}