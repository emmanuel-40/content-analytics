-- =====================================================================
-- V11 - Google sign-in (OAuth2 authorization-code flow)
--
-- Adds the columns needed to link a row in `users` to an external identity
-- provider. Existing rows keep working untouched: they all become LOCAL.
-- =====================================================================

-- Where the account's credentials come from.
ALTER TABLE users ADD COLUMN IF NOT EXISTS provider VARCHAR(20) NOT NULL DEFAULT 'LOCAL';

-- Google's immutable `sub` claim. NULL for LOCAL (password) accounts.
ALTER TABLE users ADD COLUMN IF NOT EXISTS provider_subject VARCHAR(64);

-- A Google-only account has no password. The column must be nullable so we do
-- not have to store a bcrypt hash of a secret nobody knows, which would make
-- "forgot password" look like a normal recovery path for an account that never
-- had a password in the first place.
ALTER TABLE users ALTER COLUMN password DROP NOT NULL;

-- One external identity maps to at most one row. Postgres treats NULLs as
-- distinct in a unique index, so the many LOCAL rows (provider_subject IS NULL)
-- do not collide with each other.
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_provider_subject
    ON users (provider, provider_subject);

COMMENT ON COLUMN users.provider IS 'Credential source: LOCAL (password) or GOOGLE';
COMMENT ON COLUMN users.provider_subject IS 'External identity id (Google sub claim); NULL for LOCAL accounts';
COMMENT ON COLUMN users.password IS 'bcrypt hash; NULL when the account only signs in through an identity provider';
