package com.contentanalytics.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A cross-origin POST from the SPA always starts with an OPTIONS preflight.
 *
 * When CORS lived only in MVC, Spring Security saw that OPTIONS as an
 * unauthenticated request and redirected it to the OAuth entry point. The browser
 * then refused the real POST with "Redirect is not allowed for a preflight
 * request". This test keeps the CORS filter ahead of authorization.
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("CORS preflight (integration)")
class CorsPreflightTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("refresh preflight returns CORS headers, not an OAuth redirect")
    void refreshPreflightIsAllowed() throws Exception {
        mockMvc.perform(options("/api/auth/refresh")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type,x-xsrf-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
                .andExpect(header().string("Access-Control-Allow-Methods", containsString("POST")))
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    @DisplayName("unauthenticated API call returns 401 JSON, not an OAuth redirect")
    void unauthenticatedApiReturnsJson401() throws Exception {
        mockMvc.perform(get("/api/auth/me")
                        .header("Origin", "http://localhost:3000"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status")
                        .value(401));
    }
}
