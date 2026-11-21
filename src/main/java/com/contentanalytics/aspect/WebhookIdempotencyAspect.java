package com.contentanalytics.aspect;

import com.contentanalytics.annotation.WebhookIdempotent;
import com.contentanalytics.service.WebhookIdempotencyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Aspect for {@link WebhookIdempotent}-annotated controller methods.
 *
 * Deduplicates by the provider's unique event / payment reference (not the
 * event type name). Claims the Redis key before handling; releases it if the
 * handler throws so the provider can retry safely.
 */
@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class WebhookIdempotencyAspect {

    private final WebhookIdempotencyService webhookIdempotencyService;
    private final ObjectMapper objectMapper;

    @Around("@annotation(com.contentanalytics.annotation.WebhookIdempotent)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        WebhookIdempotent ann = method.getAnnotation(WebhookIdempotent.class);

        HttpServletRequest request = getRequest();
        if (request == null) {
            return joinPoint.proceed();
        }

        String eventId = extractEventId(request, joinPoint.getArgs(), ann);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Webhook idempotency: no event id found, proceeding without dedup");
            return joinPoint.proceed();
        }

        if (webhookIdempotencyService.isDuplicate(ann.provider(), eventId)) {
            log.info("Webhook idempotency: skipping duplicate event provider={} eventId={}",
                    ann.provider(), eventId);
            return ResponseEntity.ok(Map.of("status", "duplicate", "eventId", eventId));
        }

        try {
            return joinPoint.proceed();
        } catch (Throwable t) {
            // Allow the provider to retry after a failed attempt
            webhookIdempotencyService.clear(ann.provider(), eventId);
            throw t;
        }
    }

    /**
     * Prefer header, then dotted JSON path in the body (e.g. {@code data.reference}),
     * then fall back to the raw request-body argument if the controller already
     * bound it as a String.
     */
    private String extractEventId(HttpServletRequest request, Object[] args, WebhookIdempotent ann) {
        String header = ann.eventIdHeader();
        if (header != null && !header.isBlank()) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }

        String bodyField = ann.eventIdBodyField();
        if (bodyField != null && !bodyField.isBlank()) {
            String body = resolveBody(request, args);
            if (body != null && !body.isBlank()) {
                try {
                    JsonNode node = objectMapper.readTree(body);
                    JsonNode eventNode = resolvePath(node, bodyField);
                    if (eventNode != null && !eventNode.isNull() && !eventNode.asText().isBlank()) {
                        return eventNode.asText();
                    }
                } catch (Exception e) {
                    log.debug("Could not parse webhook body for event id field {}: {}",
                            bodyField, e.getMessage());
                }
            }
        }

        return null;
    }

    private JsonNode resolvePath(JsonNode root, String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        JsonNode current = root;
        for (String part : path.split("\\.")) {
            if (current == null || current.isMissingNode()) {
                return null;
            }
            current = current.get(part);
        }
        return current;
    }

    private String resolveBody(HttpServletRequest request, Object[] args) {
        if (args != null) {
            for (Object arg : args) {
                if (arg instanceof String s && !s.isBlank() && s.trim().startsWith("{")) {
                    return s;
                }
            }
        }
        return getBody(request);
    }

    private String getBody(HttpServletRequest request) {
        try {
            StringBuilder sb = new StringBuilder();
            var reader = request.getReader();
            char[] buf = new char[1024];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
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
