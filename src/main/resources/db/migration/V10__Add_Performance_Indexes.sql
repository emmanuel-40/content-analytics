-- ============================================
-- Performance: composite + full-text indexes
-- ============================================

-- The primary list query is "my documents, newest first". A composite index lets
-- Postgres satisfy the filter AND the ordering from a single index seek instead of
-- scanning the user_id index and then sorting.
CREATE INDEX IF NOT EXISTS idx_documents_user_created
    ON documents(user_id, created_at DESC);

-- "My documents with a given status, newest first" - used by the dashboard filters.
CREATE INDEX IF NOT EXISTS idx_documents_user_status_created
    ON documents(user_id, status, created_at DESC);

-- Sentiment breakdown for the analytics dashboard (partial: only analysed rows).
CREATE INDEX IF NOT EXISTS idx_documents_user_sentiment
    ON documents(user_id, sentiment)
    WHERE sentiment IS NOT NULL;

-- Full-text search.
--
-- The previous implementation searched with extracted_text LIKE '%term%'. A leading
-- wildcard can NEVER use a btree index, so search degraded to a sequential scan as the
-- table grew. A GIN index over a tsvector turns this into an index lookup.
--
-- The generated column is maintained by Postgres itself, so it cannot drift out of sync
-- with the source columns the way an application-managed column would.
ALTER TABLE documents
    ADD COLUMN IF NOT EXISTS search_vector tsvector
        GENERATED ALWAYS AS (
            setweight(to_tsvector('english', COALESCE(file_name, '')), 'A') ||
            setweight(to_tsvector('english', COALESCE(extracted_text, '')), 'B') ||
            setweight(to_tsvector('english', COALESCE(summary, '')), 'C')
        ) STORED;

CREATE INDEX IF NOT EXISTS idx_documents_search_vector
    ON documents USING GIN(search_vector);

COMMENT ON COLUMN documents.search_vector IS
    'Weighted full-text search vector (file_name=A, extracted_text=B, summary=C), maintained by Postgres';