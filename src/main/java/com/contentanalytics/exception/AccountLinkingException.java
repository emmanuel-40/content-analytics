package com.contentanalytics.exception;

/**
 * Thrown when an identity provider cannot be safely attached to an existing local
 * account - currently only when the Google-verified email belongs to an account that
 * is already bound to a <i>different</i> Google identity.
 * <p>
 * Maps to HTTP 409 from {@link GlobalExceptionHandler}, and is converted to an
 * {@code OAuth2AuthenticationException} by the OAuth adapter so the browser is
 * redirected back to the frontend with a reason instead of seeing a stack trace.
 */
public class AccountLinkingException extends RuntimeException {

    public AccountLinkingException(String message) {
        super(message);
    }
}
