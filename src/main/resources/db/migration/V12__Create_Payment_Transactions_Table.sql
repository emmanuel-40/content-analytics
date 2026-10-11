-- =====================================================================
-- V12 - Paystack payments (test mode ready, live ready)
--
-- A payment_transactions row is created BEFORE the user pays (status PENDING)
-- and is only ever flipped to SUCCESS by the webhook handler after Paystack's
-- HMAC signature checks out AND a server-side re-query confirms the charge.
-- The UNIQUE constraint on `reference` plus the status guard makes double
-- delivery of the same webhook credit the account exactly once.
-- =====================================================================

CREATE TABLE payment_transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reference VARCHAR(100) NOT NULL UNIQUE,
    provider VARCHAR(20) NOT NULL DEFAULT 'PAYSTACK',
    amount_kobo INTEGER NOT NULL CHECK (amount_kobo > 0),
    credits INTEGER NOT NULL CHECK (credits > 0),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED', 'ABANDONED')),
    checkout_url TEXT,
    paid_at TIMESTAMP,
    raw_webhook TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_payment_tx_user_id ON payment_transactions(user_id);
CREATE INDEX IF NOT EXISTS idx_payment_tx_reference ON payment_transactions(reference);
CREATE INDEX IF NOT EXISTS idx_payment_tx_status ON payment_transactions(status);

COMMENT ON TABLE payment_transactions IS 'Paystack (or mock) checkout attempts; credits are granted only on verified SUCCESS';
COMMENT ON COLUMN payment_transactions.reference IS 'Our reference (also sent to Paystack); unique so a webhook can be applied once';
COMMENT ON COLUMN payment_transactions.amount_kobo IS 'Amount charged in kobo (NGN x 100); Paystack prices in the smallest unit';
COMMENT ON COLUMN payment_transactions.status IS 'PENDING until the verified webhook flips it; never trust the frontend callback';
