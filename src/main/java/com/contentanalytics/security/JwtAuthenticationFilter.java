package com.contentanalytics.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;
    private static final String USER_ID_ATTRIBUTE = "userId";
    private static final String EMAIL_ATTRIBUTE = "email";
    private static final String ACCESS_TOKEN_COOKIE = "accessToken";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        try {
            // Extract token from Authorization header
            String token = getJwtFromRequest(request);

            // If token exists and is valid
            if (token != null && tokenProvider.validateToken(token)) {
                // Only process ACCESS tokens, not refresh tokens
                if (tokenProvider.isAccessToken(token)) {
                    String username = tokenProvider.getUsernameFromToken(token);
                    Claims claims = tokenProvider.getClaimsFromToken(token);

                    // Create authentication object
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(username, null, new ArrayList<>());

                    // Store user info in authentication details
                    Map<String, Object> details = new HashMap<>();
                    details.put(USER_ID_ATTRIBUTE, claims.get("userId"));
                    details.put(EMAIL_ATTRIBUTE, claims.get("email"));
                    details.put("username", username);

                    authentication.setDetails(details);

                    // Set authentication in security context
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    log.debug("Set Spring Security authentication for user: {}", username);
                }
            }
        } catch (Exception e) {
            log.error("Could not set user authentication in security context", e);
        }

        // Continue with filter chain
        filterChain.doFilter(request, response);
    }

    /**
     * Extract the JWT from the request.
     * <p>
     * Order of precedence:
     * <ol>
     *   <li>{@code Authorization: Bearer <token>} header - used by non-browser clients
     *       (curl, mobile apps, service-to-service calls)</li>
     *   <li>{@code accessToken} HTTP-only cookie - the primary mechanism for browsers,
     *       because it cannot be read by injected JavaScript</li>
     * </ol>
     */
    private String getJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }

        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (ACCESS_TOKEN_COOKIE.equals(cookie.getName()) && cookie.getValue() != null
                        && !cookie.getValue().isBlank()) {
                    return cookie.getValue();
                }
            }
        }

        return null;
    }

}