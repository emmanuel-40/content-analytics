-- Create password_reset_tokens table
CREATE TABLE password_reset_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    token VARCHAR(255) NOT NULL UNIQUE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expiry_date TIMESTAMP NOT NULL,
    used_at TIMESTAMP,
    used BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Create indexes
CREATE INDEX IF NOT EXISTS idx_token ON password_reset_tokens(token);
CREATE INDEX IF NOT EXISTS idx_user_id ON password_reset_tokens(user_id);
CREATE INDEX IF NOT EXISTS idx_created_at ON password_reset_tokens(created_at);
CREATE INDEX IF NOT EXISTS idx_expiry_date ON password_reset_tokens(expiry_date);

-- Comment
COMMENT ON TABLE password_reset_tokens IS 'Password reset tokens for user account recovery';
COMMENT ON COLUMN password_reset_tokens.used IS 'Flag indicating if token has been used for password reset';
COMMENT ON COLUMN password_reset_tokens.expiry_date IS 'Token expires after this date/time (typically 1 hour)';