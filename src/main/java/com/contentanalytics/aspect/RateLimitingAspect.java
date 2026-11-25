package com.contentanalytics.aspect;

import com.contentanalytics.annotation.RateLimited;
import com.contentanalytics.service.RateLimitingService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Aspect for {@link RateLimited}-annotated controller methods.
 *
 * Enforces a Redis-backed atomic fixed-window limit per client IP (or per
 * authenticated user when {@code perUser} is true). Returns 429 Too Many
 * Requests when the limit is exceeded.
 */
@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class RateLimitingAspect {

    private final RateLimitingService rateLimitingService;

    @Around("@annotation(com.contentanalytics.annotation.RateLimited)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        RateLimited rateLimited = method.getAnnotation(RateLimited.class);

        HttpServletRequest request = getRequest();
        if (request == null) {
            return joinPoint.proceed();
        }

        String key;
        if (rateLimited.perUser()) {
            // Prefer authenticated principal - never trust a client-supplied X-User-Id
            String userId = resolveAuthenticatedUserId();
            if (userId == null || userId.isBlank()) {
                userId = "ip:" + getClientIp(request);
            }
            key = rateLimited.keyPrefix() + ":user:" + userId;
        } else {
            String ip = getClientIp(request);
            key = rateLimited.keyPrefix() + ":ip:" + ip;
        }

        if (!rateLimitingService.isAllowed(key, rateLimited.maxRequests(),
                rateLimited.windowSeconds())) {

            long ttl = rateLimitingService.getRemainingTtl(key);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(Math.max(ttl, 1)))
                    .body(Map.of("error",
                            "Rate limit exceeded. Try again in " + Math.max(ttl, 1) + " seconds."));
        }

        return joinPoint.proceed();
    }

    private String resolveAuthenticatedUserId() {
        try {
            var auth = org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()
                    || "anonymousUser".equals(String.valueOf(auth.getPrincipal()))) {
                return null;
            }
            return auth.getName();
        } catch (Exception e) {
            return null;
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp;
        }
        String remoteAddr = request.getRemoteAddr();
        return remoteAddr != null ? remoteAddr : "unknown";
    }

    private HttpServletRequest getRequest() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
            return attrs != null ? attrs.getRequest() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
