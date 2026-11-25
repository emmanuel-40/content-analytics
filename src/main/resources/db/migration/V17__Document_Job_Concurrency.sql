-- V17: optimistic lock token for document processing.
-- Two workers may both receive the same document job; @Version plus the guarded
-- status transitions in DocumentService make the second one a no-op instead of a
-- double process. Existing rows start at version 0.

ALTER TABLE documents
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN documents.version IS 'JPA optimistic lock version for guarded processing transitions';
