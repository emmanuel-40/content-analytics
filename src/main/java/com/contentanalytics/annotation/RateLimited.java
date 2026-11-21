package com.contentanalytics.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method as rate-limited using a Redis-backed sliding window.
 *
 * Limits are applied per identifier: by default the client IP, but can be scoped
 * to the authenticated user when {@code perUser()} is true.
 *
 * Safe for: login, register, payment initialize, any endpoint prone to brute
 * force or client retry storms.
 *
 * Not safe for: webhook endpoints (provider retries must not be rate-limited),
 * health checks.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimited {
    /** Max requests allowed within the window. */
    int maxRequests() default 10;

    /** Window length in seconds. */
    int windowSeconds() default 60;

    /** If true, scope the limit by authenticated user ID instead of client IP. */
    boolean perUser() default false;

    /** Key prefix for Redis, prevents collisions between unrelated endpoints. */
    String keyPrefix() default "rate";
}
