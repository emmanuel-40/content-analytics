-- Create profile_pictures table
CREATE TABLE profile_pictures (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    original_file_name VARCHAR(255) NOT NULL,
    original_file_size BIGINT NOT NULL,
    mime_type VARCHAR(50) NOT NULL,
    storage_key TEXT NOT NULL,
    thumbnail_url TEXT NOT NULL,
    medium_url TEXT NOT NULL,
    full_url TEXT NOT NULL,
    width INTEGER,
    height INTEGER,
    uploaded_from VARCHAR(50),
    ip_address VARCHAR(45),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Create indexes
CREATE INDEX IF NOT EXISTS idx_profile_pictures_user_id ON profile_pictures(user_id);
CREATE INDEX IF NOT EXISTS idx_profile_pictures_created_at ON profile_pictures(created_at);
CREATE INDEX IF NOT EXISTS idx_profile_pictures_is_active ON profile_pictures(is_active);

-- Comment
COMMENT ON TABLE profile_pictures IS 'User profile pictures with multiple sizes';
COMMENT ON COLUMN profile_pictures.thumbnail_url IS '150x150 - for avatars';
COMMENT ON COLUMN profile_pictures.medium_url IS '400x400 - for profile page';
COMMENT ON COLUMN profile_pictures.full_url IS '800x800 - for large display';