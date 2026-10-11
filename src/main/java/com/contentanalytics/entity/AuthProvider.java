package com.contentanalytics.entity;

/**
 * Where an account's credentials come from.
 * <p>
 * An account may be backed by a local password ({@link #LOCAL}), by an external
 * identity provider ({@link #GOOGLE}), or - after account linking - by both at
 * once. The columns backing this enum are populated by migration
 * {@code V11__Add_Google_OAuth_Support}.
 */
public enum AuthProvider {

    /** Username + password, hashed with BCrypt. */
    LOCAL,

    /** Signed in through Google OIDC. */
    GOOGLE
}
