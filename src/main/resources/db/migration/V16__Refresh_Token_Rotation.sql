-- V16: server-side refresh-token rotation with replay detection.
--
-- Only SHA-256 digests are stored; the raw JWT lives exclusively in the
-- HttpOnly cookie / response body. A token is claimed at most once (status
-- ACTIVE -> USED); presenting a USED/REVOKED token again revokes its whole
-- family. replaced_by is intentionally NOT a FK: the old row is updated with
-- the replacement's id by the conditional claim before the new row is flushed.

CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL,
    family_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'USED', 'REVOKED')),
    used BOOLEAN NOT NULL DEFAULT FALSE,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    used_at TIMESTAMP,
    revoked_at TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    replaced_by UUID,
    CONSTRAINT uk_refresh_tokens_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_family_id ON refresh_tokens (family_id);
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens (expires_at);
CREATE INDEX idx_refresh_tokens_status ON refresh_tokens (status);

COMMENT ON TABLE refresh_tokens IS 'Single-use refresh tokens with family-based replay detection';
COMMENT ON COLUMN refresh_tokens.token_hash IS 'Hex SHA-256 of the raw refresh JWT; raw tokens are never stored';
COMMENT ON COLUMN refresh_tokens.family_id IS 'All rotations of one login share this id';
COMMENT ON COLUMN refresh_tokens.status IS 'ACTIVE until rotated (USED) or revoked (REVOKED)';
COMMENT ON COLUMN refresh_tokens.replaced_by IS 'Id of the successor row written by the atomic claim';
