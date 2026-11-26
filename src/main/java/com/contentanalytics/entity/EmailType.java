package com.contentanalytics.entity;

/**
 * The kinds of email the platform sends. Stored as a string in the outbox so adding a
 * new template never requires a schema migration.
 */
public enum EmailType {
    EMAIL_VERIFICATION,
    PASSWORD_RESET,
    WELCOME,
    NOTIFICATION
}
