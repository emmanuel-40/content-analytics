-- V15: Align JPA @Version columns with the schema, and make credit purchases
-- idempotent at the database level (same payment reference cannot credit twice).

-- Optimistic locking columns used by Hibernate @Version on the entities.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE subscriptions
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE payment_transactions
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE credit_transactions
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- One PURCHASE ledger row per payment/provider reference (NULLs allowed for
-- monthly allocations and other non-keyed movements).
CREATE UNIQUE INDEX IF NOT EXISTS uk_credit_tx_purchase_reference
    ON credit_transactions (reference_id)
    WHERE reference_id IS NOT NULL AND type = 'PURCHASE';

-- One AI debit per document so retries / re-queues cannot charge twice.
CREATE UNIQUE INDEX IF NOT EXISTS uk_credit_tx_ai_analysis_reference
    ON credit_transactions (reference_id)
    WHERE reference_id IS NOT NULL AND type = 'AI_ANALYSIS';

COMMENT ON COLUMN users.version IS 'JPA optimistic lock version';
COMMENT ON COLUMN subscriptions.version IS 'JPA optimistic lock version';
COMMENT ON COLUMN payment_transactions.version IS 'JPA optimistic lock version';
COMMENT ON COLUMN credit_transactions.version IS 'JPA optimistic lock version';
