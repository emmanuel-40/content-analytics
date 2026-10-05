package com.contentanalytics.exception;

/**
 * Raised when a user attempts to run an AI analysis without enough credits.
 * Maps to HTTP 402 Payment Required.
 */
public class InsufficientCreditsException extends RuntimeException {

    public InsufficientCreditsException(String message) {
        super(message);
    }

    public InsufficientCreditsException(String message, Throwable cause) {
        super(message, cause);
    }

}