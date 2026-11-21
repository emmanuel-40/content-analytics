package com.contentanalytics.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a webhook-handling controller method as idempotent by provider event id.
 *
 * The aspect extracts the provider's event id from the request (header or body
 * field) and skips re-execution if the same event was already processed. Safe
 * for Paystack webhooks and any provider that retries non-2xx responses.
 *
 * Not safe for: endpoints where the client expects a different response on
 * retry, or where the event id is not available.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface WebhookIdempotent {

    /**
     * Provider name used to namespace the dedup key (e.g. "paystack").
     */
    String provider() default "paystack";

    /**
     * Header name carrying the provider's unique event id. Leave blank to use
     * {@link #eventIdBodyField()} instead.
     */
    String eventIdHeader() default "";

    /**
     * JSON body path for the unique id (supports dotted paths, e.g.
     * {@code data.reference}). Must be unique per delivery — never the event
     * type name like {@code charge.success}.
     */
    String eventIdBodyField() default "data.reference";
}
