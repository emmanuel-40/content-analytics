-- Office MIME types are longer than the original 50-character columns.
-- For example, .docx uses:
--   application/vnd.openxmlformats-officedocument.wordprocessingml.document
-- Keep the entity and database widths aligned so uploads do not fail on commit.
ALTER TABLE documents
    ALTER COLUMN mime_type TYPE VARCHAR(255);

ALTER TABLE profile_pictures
    ALTER COLUMN mime_type TYPE VARCHAR(255);

COMMENT ON COLUMN documents.mime_type IS 'Uploaded file MIME type, including long Office MIME types';
COMMENT ON COLUMN profile_pictures.mime_type IS 'Uploaded profile image MIME type';
