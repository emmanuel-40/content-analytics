package com.contentanalytics.interceptor;

import com.contentanalytics.service.RateLimitingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Distributed login/register throttling.
 *
 * The old Bucket4j implementation kept buckets in a local ConcurrentHashMap,
 * so an attacker could multiply the limit by the number of backend instances.
 * Counters now live in Redis and are updated with one atomic Lua script.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RateLimitingInterceptor implements HandlerInterceptor {

    private final RateLimitingService rateLimitingService;

    @Value("${app.rate-limit.enabled:true}")
    private boolean rateLimitingEnabled;

    @Value("${app.rate-limit.login.max-requests:5}")
    private int loginMaxRequests;

    @Value("${app.rate-limit.login.window-seconds:900}")
    private int loginWindowSeconds;

    @Value("${app.rate-limit.register.max-requests:3}")
    private int registerMaxRequests;

    @Value("${app.rate-limit.register.window-seconds:900}")
    private int registerWindowSeconds;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!rateLimitingEnabled) {
            return true;
        }
        String clientIp = getClientIp(request);
        String requestPath = request.getRequestURI();

        if (requestPath.contains("/auth/login")) {
            return checkRateLimit(response, "login:" + clientIp,
                    loginMaxRequests, loginWindowSeconds, "login", clientIp);
        }

        if (requestPath.contains("/auth/register")) {
            return checkRateLimit(response, "register:" + clientIp,
                    registerMaxRequests, registerWindowSeconds, "register", clientIp);
        }

        return true;
    }

    private boolean checkRateLimit(HttpServletResponse response, String key,
                                   int maxRequests, int windowSeconds,
                                   String endpoint, String clientIp) {
        RateLimitingService.RateLimitResult result =
                rateLimitingService.check(key, maxRequests, windowSeconds);

        if (result.allowed()) {
            response.addHeader("X-Rate-Limit-Remaining", String.valueOf(result.remaining()));
            return true;
        }

        long retryAfter = Math.max(result.retryAfterSeconds(), 1L);
        response.setStatus(429); // SC_TOO_MANY_REQUESTS is not in all Servlet API versions
        response.setHeader("Retry-After", String.valueOf(retryAfter));
        response.setContentType("application/json");
        try {
            response.getWriter().write(
                    "{\"status\":429,\"message\":\"Too many " + endpoint
                            + " attempts. Try again in " + retryAfter + " seconds\"}");
        } catch (java.io.IOException e) {
            log.warn("Could not write rate-limit response for {}: {}", endpoint, e.getMessage());
        }

        log.warn("Rate limit exceeded for {} from IP: {}", endpoint, clientIp);
        return false;
    }

    /** Only trust the first proxy hop; X-Forwarded-For is appended by proxies. */
    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String remoteAddr = request.getRemoteAddr();
        return remoteAddr != null ? remoteAddr : "unknown";
    }
}
