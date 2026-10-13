-- Create documents table
CREATE TABLE documents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    file_name VARCHAR(500) NOT NULL,
    file_key TEXT NOT NULL,
    file_size BIGINT NOT NULL,
    mime_type VARCHAR(50) NOT NULL,
    page_count INTEGER NOT NULL DEFAULT 0,
    description VARCHAR(1000),
    status VARCHAR(50) NOT NULL DEFAULT 'UPLOADED' CHECK (status IN ('UPLOADED', 'PROCESSING', 'COMPLETED', 'FAILED')),
    processing_progress FLOAT,
    processing_error TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processing_started_at TIMESTAMP,
    processing_completed_at TIMESTAMP,
    extracted_text TEXT,
    word_count INTEGER,
    character_count INTEGER,
    summary TEXT,
    key_topics TEXT,
    sentiment VARCHAR(50),
    confidence_score FLOAT
);

-- Create indexes for performance
CREATE INDEX IF NOT EXISTS idx_documents_user_id ON documents(user_id);
CREATE INDEX IF NOT EXISTS idx_documents_status ON documents(status);
CREATE INDEX IF NOT EXISTS idx_documents_created_at ON documents(created_at);
CREATE INDEX IF NOT EXISTS idx_documents_file_name ON documents(file_name);
CREATE INDEX IF NOT EXISTS idx_documents_sentiment ON documents(sentiment);

-- Comments
COMMENT ON TABLE documents IS 'User documents with AI analysis metadata';
COMMENT ON COLUMN documents.status IS 'Processing status: UPLOADED, PROCESSING, COMPLETED, FAILED';
COMMENT ON COLUMN documents.processing_progress IS 'Processing progress as percentage (0-100)';
COMMENT ON COLUMN documents.extracted_text IS 'Raw text extracted from document';
COMMENT ON COLUMN documents.summary IS 'AI-generated summary of document content';
COMMENT ON COLUMN documents.key_topics IS 'JSON array of key topics extracted from document';
COMMENT ON COLUMN documents.sentiment IS 'Sentiment analysis result: POSITIVE, NEUTRAL, NEGATIVE';
COMMENT ON COLUMN documents.confidence_score IS 'Confidence score of AI analysis (0-1)';