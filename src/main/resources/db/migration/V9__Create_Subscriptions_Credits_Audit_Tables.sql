-- Create subscriptions table (freemium tiers)
CREATE TABLE subscriptions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    tier VARCHAR(20) NOT NULL DEFAULT 'FREE' CHECK (tier IN ('FREE', 'PREMIUM', 'ENTERPRISE')),
    monthly_credit_allowance INTEGER NOT NULL DEFAULT 0,
    credits_used_this_period INTEGER NOT NULL DEFAULT 0,
    purchased_credits INTEGER NOT NULL DEFAULT 0,
    period_start TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    period_end TIMESTAMP NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL '30 days'),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'CANCELLED', 'EXPIRED', 'PAST_DUE')),
    auto_renew BOOLEAN NOT NULL DEFAULT TRUE,
    external_customer_id VARCHAR(255),
    external_subscription_id VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_subscription_user_id ON subscriptions(user_id);
CREATE INDEX IF NOT EXISTS idx_subscription_tier ON subscriptions(tier);
CREATE INDEX IF NOT EXISTS idx_subscription_status ON subscriptions(status);
CREATE INDEX IF NOT EXISTS idx_subscription_period_end ON subscriptions(period_end);

COMMENT ON TABLE subscriptions IS 'User subscription tier and monthly credit allowance';
COMMENT ON COLUMN subscriptions.tier IS 'Subscription tier: FREE, PREMIUM, ENTERPRISE';
COMMENT ON COLUMN subscriptions.monthly_credit_allowance IS 'Credits granted per billing period';
COMMENT ON COLUMN subscriptions.credits_used_this_period IS 'Credits consumed in the current period';
COMMENT ON COLUMN subscriptions.purchased_credits IS 'Pay-as-you-go credits; survive the monthly reset';
COMMENT ON COLUMN subscriptions.period_end IS 'When the current credit period resets';

-- Create credit_transactions table (immutable ledger of credit movements)
CREATE TABLE credit_transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    amount INTEGER NOT NULL,
    balance_after INTEGER NOT NULL,
    type VARCHAR(30) NOT NULL CHECK (type IN ('MONTHLY_ALLOCATION', 'PURCHASE', 'AI_ANALYSIS', 'REFUND', 'ADMIN_ADJUSTMENT', 'EXPIRY')),
    description VARCHAR(500),
    reference_id VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_credit_tx_user_id ON credit_transactions(user_id);
CREATE INDEX IF NOT EXISTS idx_credit_tx_created_at ON credit_transactions(created_at);
CREATE INDEX IF NOT EXISTS idx_credit_tx_type ON credit_transactions(type);

COMMENT ON TABLE credit_transactions IS 'Append-only ledger of all credit movements';
COMMENT ON COLUMN credit_transactions.amount IS 'Signed change: positive adds credits, negative consumes';
COMMENT ON COLUMN credit_transactions.balance_after IS 'Running balance after this transaction';
COMMENT ON COLUMN credit_transactions.type IS 'Reason for the credit movement';

-- Audit log for security-relevant and billing events
CREATE TABLE audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    username VARCHAR(100),
    action VARCHAR(100) NOT NULL,
    entity_type VARCHAR(50),
    entity_id VARCHAR(255),
    details TEXT,
    ip_address VARCHAR(45),
    user_agent VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_audit_user_id ON audit_logs(user_id);
CREATE INDEX IF NOT EXISTS idx_audit_action ON audit_logs(action);
CREATE INDEX IF NOT EXISTS idx_audit_created_at ON audit_logs(created_at);

COMMENT ON TABLE audit_logs IS 'Audit trail of security and billing actions';

-- Seed every existing user onto the FREE tier
INSERT INTO subscriptions (user_id, tier, monthly_credit_allowance, period_start, period_end, status)
SELECT u.id, 'FREE', 5,
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP + INTERVAL '30 days',
       'ACTIVE'
FROM users u
WHERE NOT EXISTS (SELECT 1 FROM subscriptions s WHERE s.user_id = u.id);