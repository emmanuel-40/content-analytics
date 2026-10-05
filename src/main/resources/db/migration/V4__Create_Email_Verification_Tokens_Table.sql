-- Create email_verification_tokens table
CREATE TABLE email_verification_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    token VARCHAR(255) NOT NULL UNIQUE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expiry_date TIMESTAMP NOT NULL,
    verified_at TIMESTAMP,
    used BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Create indexes
CREATE INDEX IF NOT EXISTS idx_token ON email_verification_tokens(token);
CREATE INDEX IF NOT EXISTS idx_user_id ON email_verification_tokens(user_id);
CREATE INDEX IF NOT EXISTS idx_created_at ON email_verification_tokens(created_at);
CREATE INDEX IF NOT EXISTS idx_expiry_date ON email_verification_tokens(expiry_date);

-- Comment
COMMENT ON TABLE email_verification_tokens IS 'Email verification tokens for new user registration';
COMMENT ON COLUMN email_verification_tokens.used IS 'Flag indicating if token has been used for verification';
COMMENT ON COLUMN email_verification_tokens.expiry_date IS 'Token expires after this date/time';