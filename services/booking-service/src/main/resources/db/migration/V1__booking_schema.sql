-- booking-service owns reservations and their history. Room and payment data are referenced by id
-- only (no cross-database foreign keys); the copies kept here are snapshots taken at booking time.

CREATE TABLE bookings (
    id                  UUID PRIMARY KEY,
    reference           VARCHAR(12)    NOT NULL,
    customer_id         UUID           NOT NULL,
    customer_email      VARCHAR(254)   NOT NULL,
    room_id             UUID           NOT NULL,
    room_number         VARCHAR(20),
    room_name           VARCHAR(120),
    room_type_name      VARCHAR(80),
    check_in            DATE           NOT NULL,
    check_out           DATE           NOT NULL,
    nights              SMALLINT       NOT NULL CHECK (nights > 0),
    guests              SMALLINT       NOT NULL CHECK (guests BETWEEN 1 AND 12),
    status              VARCHAR(24)    NOT NULL CHECK (status IN ('CREATED', 'PENDING_PAYMENT', 'CONFIRMED', 'PAYMENT_FAILED',
                                                                  'EXPIRED', 'REJECTED', 'CANCELLATION_PENDING', 'CANCELLED',
                                                                  'NEEDS_ATTENTION')),
    idempotency_key     VARCHAR(80)    NOT NULL,
    request_fingerprint VARCHAR(64)    NOT NULL,
    payment_id          UUID,
    hold_expires_at     TIMESTAMPTZ,
    total_amount        NUMERIC(12, 2) CHECK (total_amount > 0),
    currency            VARCHAR(3),
    refund_amount       NUMERIC(12, 2) CHECK (refund_amount >= 0),
    status_reason       VARCHAR(80),
    special_requests    VARCHAR(500),
    confirmed_at        TIMESTAMPTZ,
    cancelled_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ    NOT NULL,
    updated_at          TIMESTAMPTZ    NOT NULL,
    version             BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT ux_bookings_reference UNIQUE (reference),
    -- duplicate submissions with the same Idempotency-Key map to the same booking
    CONSTRAINT ux_bookings_idempotency UNIQUE (customer_id, idempotency_key),
    CONSTRAINT ck_bookings_dates CHECK (check_out > check_in),
    CONSTRAINT ck_bookings_refund_le_total CHECK (refund_amount IS NULL OR refund_amount <= total_amount)
);
CREATE INDEX ix_bookings_customer ON bookings (customer_id, check_in DESC);
CREATE INDEX ix_bookings_status_updated ON bookings (status, updated_at);
CREATE INDEX ix_bookings_check_in ON bookings (check_in);
CREATE INDEX ix_bookings_payment ON bookings (payment_id);

CREATE TABLE booking_guests (
    id         UUID PRIMARY KEY,
    booking_id UUID         NOT NULL REFERENCES bookings (id) ON DELETE CASCADE,
    full_name  VARCHAR(120) NOT NULL,
    email      VARCHAR(254),
    phone      VARCHAR(32),
    is_primary BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE INDEX ix_booking_guests_booking ON booking_guests (booking_id);
CREATE UNIQUE INDEX ux_booking_primary_guest ON booking_guests (booking_id) WHERE is_primary;

-- Immutable price snapshot from the room-service quote used for this booking.
CREATE TABLE booking_price_snapshots (
    booking_id             UUID PRIMARY KEY REFERENCES bookings (id) ON DELETE CASCADE,
    pricing_policy_version INTEGER        NOT NULL,
    nights                 SMALLINT       NOT NULL,
    subtotal               NUMERIC(12, 2) NOT NULL,
    service_fee            NUMERIC(12, 2) NOT NULL,
    taxes                  NUMERIC(12, 2) NOT NULL,
    total                  NUMERIC(12, 2) NOT NULL,
    currency               VARCHAR(3)     NOT NULL,
    quoted_at              TIMESTAMPTZ    NOT NULL,
    nightly_rates          JSONB          NOT NULL,
    CONSTRAINT ck_snapshot_total CHECK (total = subtotal + service_fee + taxes)
);

-- Auditable history of every lifecycle transition.
CREATE TABLE booking_status_history (
    id          BIGSERIAL PRIMARY KEY,
    booking_id  UUID        NOT NULL REFERENCES bookings (id) ON DELETE CASCADE,
    from_status VARCHAR(24),
    to_status   VARCHAR(24) NOT NULL,
    reason      VARCHAR(255),
    actor       VARCHAR(80) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_booking_history_booking ON booking_status_history (booking_id, occurred_at);

-- Inbox: payment events already applied (at-least-once delivery is deduplicated here).
CREATE TABLE processed_events (
    event_id     UUID PRIMARY KEY,
    event_type   VARCHAR(40) NOT NULL,
    booking_id   UUID        NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL
);
