-- Transactional outbox for booking domain events (BOOKING_CREATED, BOOKING_CONFIRMED, BOOKING_CANCELLED,
-- HOLD_EXPIRED). Rows are written in the same transaction as the status change and published to RabbitMQ
-- afterwards (at-least-once; consumers de-duplicate by event id).
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
CREATE INDEX ix_outbox_aggregate ON outbox_events (aggregate_id);
