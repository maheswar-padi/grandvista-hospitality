-- payment-service owns payment intents, attempts, refunds, webhook receipts and its outbox.
-- No card numbers, CVVs or other card authentication data are ever stored: the provider returns opaque
-- references and the client only ever sends provider-issued payment-method tokens.

CREATE TABLE payments (
    id                 UUID PRIMARY KEY,
    booking_id         UUID           NOT NULL,
    booking_reference  VARCHAR(20)    NOT NULL,
    customer_id        UUID           NOT NULL,
    amount             NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    currency           VARCHAR(3)     NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    status             VARCHAR(24)    NOT NULL CHECK (status IN ('CREATED', 'PENDING', 'SUCCEEDED', 'FAILED', 'CANCELLED',
                                                                 'REFUND_PENDING', 'PARTIALLY_REFUNDED', 'REFUNDED')),
    provider           VARCHAR(40)    NOT NULL,
    provider_reference VARCHAR(80),
    failure_code       VARCHAR(60),
    failure_message    VARCHAR(255),
    refunded_amount    NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (refunded_amount >= 0),
    expires_at         TIMESTAMPTZ    NOT NULL,
    created_at         TIMESTAMPTZ    NOT NULL,
    updated_at         TIMESTAMPTZ    NOT NULL,
    version            BIGINT         NOT NULL DEFAULT 0,
    -- one payment per booking: the booking id is the idempotency key for payment creation
    CONSTRAINT ux_payments_booking UNIQUE (booking_id),
    CONSTRAINT ux_payments_provider_reference UNIQUE (provider, provider_reference),
    CONSTRAINT ck_payments_refund_le_amount CHECK (refunded_amount <= amount)
);
CREATE INDEX ix_payments_customer ON payments (customer_id, created_at DESC);
CREATE INDEX ix_payments_status ON payments (status, updated_at);

-- Every customer confirmation call; (payment_id, idempotency_key) makes retries safe.
CREATE TABLE payment_attempts (
    id                 UUID PRIMARY KEY,
    payment_id         UUID        NOT NULL REFERENCES payments (id),
    idempotency_key    VARCHAR(80) NOT NULL,
    payment_method     VARCHAR(40) NOT NULL,
    status             VARCHAR(20) NOT NULL CHECK (status IN ('PROCESSING', 'SUCCEEDED', 'FAILED', 'PENDING_PROVIDER', 'UNKNOWN', 'REJECTED')),
    provider_reference VARCHAR(80),
    failure_code       VARCHAR(60),
    created_at         TIMESTAMPTZ NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL,
    CONSTRAINT ux_attempts_idempotency UNIQUE (payment_id, idempotency_key)
);

CREATE TABLE refunds (
    id                 UUID PRIMARY KEY,
    payment_id         UUID           NOT NULL REFERENCES payments (id),
    amount             NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    status             VARCHAR(20)    NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    reason             VARCHAR(80)    NOT NULL,
    idempotency_key    VARCHAR(80)    NOT NULL,
    provider_reference VARCHAR(80),
    failure_message    VARCHAR(255),
    created_at         TIMESTAMPTZ    NOT NULL,
    updated_at         TIMESTAMPTZ    NOT NULL,
    CONSTRAINT ux_refunds_idempotency UNIQUE (idempotency_key)
);
CREATE INDEX ix_refunds_payment ON refunds (payment_id);

-- Audit trail of every state change.
CREATE TABLE payment_events (
    id          BIGSERIAL PRIMARY KEY,
    payment_id  UUID        NOT NULL REFERENCES payments (id),
    event_type  VARCHAR(40) NOT NULL,
    from_status VARCHAR(24),
    to_status   VARCHAR(24),
    actor       VARCHAR(80) NOT NULL,
    detail      VARCHAR(500),
    occurred_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_payment_events_payment ON payment_events (payment_id, occurred_at);

-- Provider callbacks, deduplicated by the provider's event id.
CREATE TABLE webhook_events (
    id                UUID PRIMARY KEY,
    provider          VARCHAR(40) NOT NULL,
    provider_event_id VARCHAR(80) NOT NULL,
    event_type        VARCHAR(60) NOT NULL,
    payload_sha256    VARCHAR(64) NOT NULL,
    outcome           VARCHAR(20) NOT NULL CHECK (outcome IN ('PROCESSED', 'IGNORED')),
    received_at       TIMESTAMPTZ NOT NULL,
    CONSTRAINT ux_webhook_events UNIQUE (provider, provider_event_id)
);

-- Transactional outbox: events are written in the same transaction as the state change and delivered
-- to the booking-service afterwards (at-least-once; the consumer deduplicates by event id).
CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY,
    aggregate_id    UUID         NOT NULL,
    event_type      VARCHAR(40)  NOT NULL,
    payload         JSONB        NOT NULL,
    correlation_id  VARCHAR(64),
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING', 'SENT', 'DEAD')),
    attempts        INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    last_error      VARCHAR(500),
    created_at      TIMESTAMPTZ  NOT NULL,
    sent_at         TIMESTAMPTZ
);
CREATE INDEX ix_outbox_pending ON outbox_events (next_attempt_at) WHERE status = 'PENDING';

-- ---------------------------------------------------------------------------------------------------
-- SIMULATED PROVIDER. This schema stands in for an external payment provider's own records so that
-- idempotent charges, delayed webhooks and "processed but response lost" timeouts can be demonstrated.
-- No real money is ever moved. A real provider integration would not use these tables.
-- ---------------------------------------------------------------------------------------------------
CREATE SCHEMA simulated_provider;

CREATE TABLE simulated_provider.charges (
    id               UUID PRIMARY KEY,
    reference        VARCHAR(80)    NOT NULL UNIQUE,
    idempotency_key  VARCHAR(80)    NOT NULL UNIQUE,
    amount           NUMERIC(12, 2) NOT NULL,
    currency         VARCHAR(3)     NOT NULL,
    payment_method   VARCHAR(40)    NOT NULL,
    status           VARCHAR(20)    NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    failure_code     VARCHAR(60),
    final_status     VARCHAR(20)    NOT NULL CHECK (final_status IN ('SUCCEEDED', 'FAILED')),
    webhook_due_at   TIMESTAMPTZ,
    webhook_sent_at  TIMESTAMPTZ,
    webhook_attempts INTEGER        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ    NOT NULL
);
CREATE INDEX ix_sim_charges_webhook_due ON simulated_provider.charges (webhook_due_at)
    WHERE webhook_sent_at IS NULL AND webhook_due_at IS NOT NULL;

CREATE TABLE simulated_provider.refunds (
    id              UUID PRIMARY KEY,
    reference       VARCHAR(80)    NOT NULL UNIQUE,
    idempotency_key VARCHAR(80)    NOT NULL UNIQUE,
    charge_ref      VARCHAR(80)    NOT NULL REFERENCES simulated_provider.charges (reference),
    amount          NUMERIC(12, 2) NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL
);
