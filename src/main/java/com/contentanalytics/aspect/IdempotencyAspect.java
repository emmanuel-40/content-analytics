package com.contentanalytics.aspect;

import com.contentanalytics.annotation.Idempotent;
import com.contentanalytics.service.IdempotencyService;
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
 * Aspect for {@link Idempotent}-annotated controller methods.
 *
 * Extracts the {@code Idempotency-Key} header, checks Redis for a cached
 * response, and returns it on duplicate requests — preventing double charges,
 * duplicate credit grants, or duplicate checkout rows on client retry.
 */
@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class IdempotencyAspect {

    private final IdempotencyService idempotencyService;

    @Around("@annotation(com.contentanalytics.annotation.Idempotent)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Idempotent idempotent = method.getAnnotation(Idempotent.class);

        HttpServletRequest request = getRequest();
        if (request == null) {
            return joinPoint.proceed();
        }

        String key = request.getHeader("Idempotency-Key");

        if (key == null || key.isBlank()) {
            if (idempotent.required()) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error",
                                "Idempotency-Key header is required for this endpoint"));
            }
            return joinPoint.proceed();
        }

        String endpoint = getEndpointKey(method, request);

        IdempotencyService.CachedResponse<Object> cached =
                idempotencyService.execute(key, endpoint, idempotent.ttlHours(),
                        () -> {
                            Object result;
                            try {
                                result = joinPoint.proceed();
                            } catch (Throwable t) {
                                throw new RuntimeException(t);
                            }
                            return toCached(result);
                        });

        return ResponseEntity.status(cached.status()).body(cached.body());
    }

    private IdempotencyService.CachedResponse<Object> toCached(Object result) {
        if (result instanceof ResponseEntity<?> re) {
            return new IdempotencyService.CachedResponse<>(
                    re.getStatusCode().value(),
                    Map.of(),
                    re.getBody());
        }
        return new IdempotencyService.CachedResponse<>(200, Map.of(), result);
    }

    private String getEndpointKey(Method method, HttpServletRequest request) {
        String className = method.getDeclaringClass().getSimpleName();
        String methodName = method.getName();
        String path = request.getRequestURI();
        return className + "." + methodName + ":" + path;
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
