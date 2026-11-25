-- =====================================================================
-- V18 - Price credit packs in USD cents instead of NGN kobo.
--
-- A Nigerian Paystack integration can accept USD in both test and live
-- mode. The application now sends currency=USD on initialize, so every
-- amount stored here must be in cents (USD x 100) for the amount guard
-- in PaymentService to compare like-for-like with Paystack's response.
-- =====================================================================

ALTER TABLE payment_transactions
    RENAME COLUMN amount_kobo TO amount_cents;

-- Only sandbox/test rows exist so far. Convert the known packs to their
-- USD equivalents; any other historical amount uses a conservative
-- NGN->USD rate and is clamped to Paystack's USD minimum of $2.00.
UPDATE payment_transactions
SET amount_cents = CASE amount_cents
    WHEN 100000 THEN 200
    WHEN 400000 THEN 800
    WHEN 800000 THEN 1600
    ELSE GREATEST(200, amount_cents / 500)
END;

COMMENT ON COLUMN payment_transactions.amount_cents IS
    'Amount charged in USD cents (USD x 100); Paystack prices in the smallest unit';
