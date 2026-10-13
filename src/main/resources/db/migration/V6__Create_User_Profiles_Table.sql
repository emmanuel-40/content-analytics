-- Create user_profiles table
CREATE TABLE user_profiles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    bio VARCHAR(500),
    phone_number VARCHAR(50),
    location VARCHAR(100),
    timezone VARCHAR(50),
    website VARCHAR(255),
    company VARCHAR(100),
    job_title VARCHAR(100),
    email_notifications BOOLEAN NOT NULL DEFAULT TRUE,
    two_factor_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    public_profile BOOLEAN NOT NULL DEFAULT FALSE,
    show_email_publicly BOOLEAN NOT NULL DEFAULT FALSE,
    total_documents_processed INTEGER NOT NULL DEFAULT 0,
    total_analysis_requests INTEGER NOT NULL DEFAULT 0,
    last_profile_update_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Create indexes
CREATE INDEX IF NOT EXISTS idx_user_profiles_user_id ON user_profiles(user_id);
CREATE INDEX IF NOT EXISTS idx_user_profiles_updated_at ON user_profiles(updated_at);

-- Comment
COMMENT ON TABLE user_profiles IS 'Extended user profile information beyond basic auth';