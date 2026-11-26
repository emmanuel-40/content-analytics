-- =====================================================================
-- V20 - Transactional outbox for email delivery.
--
-- Email requests are stored in the same transaction as the state change that
-- caused them (registration, token creation, password change). A publisher
-- hands them to RabbitMQ; the consumer marks them SENT. The unique dedupe key
-- makes both resend storms and duplicated broker deliveries harmless.
-- =====================================================================

CREATE TABLE email_outbox (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dedupe_key    VARCHAR(255) NOT NULL UNIQUE,
    type          VARCHAR(50) NOT NULL,
    recipient     VARCHAR(255) NOT NULL,
    display_name  VARCHAR(255),
    subject       VARCHAR(255),
    html_content  TEXT,
    status        VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts      INTEGER NOT NULL DEFAULT 0,
    last_error    VARCHAR(2000),
    created_at    TIMESTAMP NOT NULL DEFAULT now(),
    published_at  TIMESTAMP,
    sent_at       TIMESTAMP
);

CREATE INDEX idx_email_outbox_status_created ON email_outbox (status, created_at);

COMMENT ON TABLE email_outbox IS
    'Transactional email queue written with the originating DB transaction';
COMMENT ON COLUMN email_outbox.dedupe_key IS
    'Stable logical email key; unique constraint suppresses duplicates and redeliveries';
