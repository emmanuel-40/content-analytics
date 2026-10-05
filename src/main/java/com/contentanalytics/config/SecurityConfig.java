package com.contentanalytics.config;

import com.contentanalytics.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    /**
     * Password encoder with 12 rounds (strong hashing)
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * Authentication manager for processing authentication
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        // CSRF Token Handler - BREACH protection for a browser SPA.
        // SpaCsrfTokenRequestHandler presents the masked token (via GET /api/auth/csrf)
        // but also accepts the raw cookie value a client can read from document.cookie.
        SpaCsrfTokenRequestHandler csrfTokenRequestAttributeHandler =
                new SpaCsrfTokenRequestHandler();
        csrfTokenRequestAttributeHandler.setCsrfRequestAttributeName("_csrf");

        http
                // CSRF Protection with secure cookie handling
                .csrf(csrf -> csrf
                        // Use CookieCsrfTokenRepository for stateless APIs
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        // This allows JavaScript to read the CSRF token from the cookie
                        // Double-submit CSRF for a cookie-authenticated SPA.
                        //
                        // Uses the plain handler, NOT XorCsrfTokenRequestAttributeHandler.
                        // The XOR handler is BREACH mitigation for server-rendered templates:
                        // it masks the token in the request attribute but leaves the raw
                        // value in the cookie, so a client that reads the cookie and echoes
                        // it back in the header always fails with 403. Verified against a
                        // running instance - this is the configuration a browser SPA needs.
                        .csrfTokenRequestHandler(csrfTokenRequestAttributeHandler)
                        // CSRF is only disabled for the genuinely public, unauthenticated
                        // auth endpoints.
                        //
                        // Everything that authenticates via the accessToken cookie
                        // (documents, profile, subscription) MUST keep CSRF protection on -
                        // otherwise a malicious site could issue state-changing requests that
                        // the browser attaches the victim's cookies to. Disabling CSRF there
                        // was a real vulnerability.
                        //
                        // GET/HEAD are safe methods and never need a token.
                        .ignoringRequestMatchers(
                                "/api/auth/register",
                                "/api/auth/login",
                                "/api/auth/refresh",
                                "/api/auth/verify-email",
                                "/api/auth/resend-verification-email",
                                "/api/auth/forgot-password",
                                "/api/auth/reset-password",
                                "/api/auth/verify-token/**"
                        )
                        // Swagger UI and WebSocket handshake are not browser-session driven
                        .ignoringRequestMatchers(
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/ws/**"
                        )
                )
                // Session management - stateless (JWT based)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                // Authorization rules
                .authorizeHttpRequests(authz -> authz
                        // Public endpoints (no auth needed)
                        .requestMatchers(HttpMethod.POST, "/api/auth/register").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/refresh").permitAll()  // Token validated in service
                        .requestMatchers(HttpMethod.GET, "/api/auth/verify-email").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/resend-verification-email").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/forgot-password").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/reset-password").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/verify-token/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()

                        // Protected endpoints (need JWT)
                        .requestMatchers(HttpMethod.POST, "/api/auth/logout").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/auth/me").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/auth/invalidate-tokens").authenticated()

                        // Health & public
                        .requestMatchers("/api/health/**").permitAll()

                        // API documentation (safe to expose - spec only, no secrets)
                        .requestMatchers("/v3/api-docs/**").permitAll()
                        .requestMatchers("/swagger-ui/**").permitAll()
                        .requestMatchers("/swagger-ui.html").permitAll()

                        // Actuator: only the health probe and metrics are exposed
                        // (see management.endpoints.web.exposure.include). Everything
                        // else remains authenticated.
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/info").permitAll()
                        .requestMatchers("/actuator/prometheus").permitAll()
                        .requestMatchers("/actuator/**").authenticated()

                        .requestMatchers("/ws/**").permitAll()

                        // Everything else protected
                        .anyRequest().authenticated()
                )
                // Add JWT filter before authentication filter
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // Ensure the XSRF-TOKEN cookie is issued so the SPA can send it back
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class);

        return http.build();
    }

}