package com.contentanalytics.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method as idempotent: the client must supply an
 * {@code Idempotency-Key} header, and duplicate requests with the same key
 * within the TTL window return the cached response instead of re-executing.
 *
 * Safe for: POST /api/payments/initialize, POST /api/subscription/purchase-credits,
 * any mutation where a client retry must not create a duplicate side effect.
 *
 * Not safe for: GET endpoints (read-only), webhook endpoints (provider-driven,
 * deduplicated by business-key instead).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {
    /**
     * TTL in hours after which the cached response expires.
     */
    long ttlHours() default 24;

    /**
     * Whether the idempotency key is mandatory (409 if missing) or optional
     * (proceed without dedupe if absent).
     */
    boolean required() default true;
}
