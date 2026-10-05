-- Create users table
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(100) NOT NULL UNIQUE,
    email VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    first_name VARCHAR(255),
    last_name VARCHAR(255),
    role VARCHAR(50) NOT NULL DEFAULT 'USER' CHECK (role IN ('ADMIN', 'USER')),
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE', 'BANNED')),
    token_version INTEGER NOT NULL DEFAULT 0,
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    email_verified_at TIMESTAMP,
    last_login_at TIMESTAMP,
    last_password_reset_at TIMESTAMP,
    last_password_change_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Create indexes
CREATE INDEX IF NOT EXISTS idx_username ON users(username);
CREATE INDEX IF NOT EXISTS idx_email ON users(email);
CREATE INDEX IF NOT EXISTS idx_status ON users(status);
CREATE INDEX IF NOT EXISTS idx_email_verified ON users(email_verified);
CREATE INDEX IF NOT EXISTS idx_created_at ON users(created_at);

-- Comments
COMMENT ON TABLE users IS 'User accounts with authentication and profile data';
COMMENT ON COLUMN users.role IS 'User role: ADMIN or USER';
COMMENT ON COLUMN users.status IS 'User status: ACTIVE, INACTIVE, or BANNED';
COMMENT ON COLUMN users.token_version IS 'Used for token rotation when password changes';
COMMENT ON COLUMN users.email_verified IS 'Whether email has been verified';
COMMENT ON COLUMN users.email_verified_at IS 'Timestamp when email was verified';
COMMENT ON COLUMN users.last_login_at IS 'Timestamp of last login';
COMMENT ON COLUMN users.last_password_reset_at IS 'Timestamp of last password reset';
COMMENT ON COLUMN users.last_password_change_at IS 'Timestamp of last password change';