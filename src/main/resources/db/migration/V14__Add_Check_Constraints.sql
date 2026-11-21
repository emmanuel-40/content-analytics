-- V14__Add_Check_Constraints.sql
-- Add database-level CHECK constraints so bad data is rejected even if
-- a bug slips past the application layer. These are enforced by Postgres
-- regardless of which service instance writes the row.

-- payment_transactions: positive amounts and credit counts
ALTER TABLE payment_transactions
    ADD CONSTRAINT chk_payment_amount_positive CHECK (amount_kobo > 0);

ALTER TABLE payment_transactions
    ADD CONSTRAINT chk_payment_credits_positive CHECK (credits > 0);

-- subscriptions: non-negative credit counters, sane period bounds
ALTER TABLE subscriptions
    ADD CONSTRAINT chk_subscription_credits_used_nonneg CHECK (credits_used_this_period >= 0);

ALTER TABLE subscriptions
    ADD CONSTRAINT chk_subscription_purchased_nonneg CHECK (purchased_credits >= 0);

ALTER TABLE subscriptions
    ADD CONSTRAINT chk_subscription_period_valid CHECK (period_end > period_start);

-- credit_transactions: non-zero amount (every ledger move changes the balance)
ALTER TABLE credit_transactions
    ADD CONSTRAINT chk_credit_tx_amount_nonzero CHECK (amount <> 0);
