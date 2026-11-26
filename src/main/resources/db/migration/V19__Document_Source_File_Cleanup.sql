-- =====================================================================
-- V19 - Track removal of the original upload after analysis.
--
-- Documents keep only their analysis results. The source file is deleted
-- as soon as processing completes; this flag lets the cleanup scheduler
-- retry any deletion that failed because of a temporary storage error.
-- =====================================================================

ALTER TABLE documents
    ADD COLUMN source_file_deleted BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN documents.source_file_deleted IS
    'True when the original upload has been removed from storage after analysis';
