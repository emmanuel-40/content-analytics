package com.contentanalytics.entity;

/**
 * Lifecycle of a persisted refresh token.
 *
 * A token is {@link #ACTIVE} from the moment it is minted until it is either
 * rotated ({@link #USED}) or explicitly killed ({@link #REVOKED}). Only ACTIVE
 * tokens can be claimed; a second presentation of a USED/REVOKED row is the
 * replay signal that triggers family-wide revocation.
 */
public enum RefreshTokenStatus {
    ACTIVE,
    USED,
    REVOKED
}
