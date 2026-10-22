-- room-service owns rooms, inventory allocations and pricing rules.
-- btree_gist is a trusted extension (PostgreSQL 13+): the database owner can create it.
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE room_types (
    id          UUID PRIMARY KEY,
    code        VARCHAR(40)  NOT NULL UNIQUE,
    name        VARCHAR(80)  NOT NULL,
    description VARCHAR(1000),
    sort_order  INTEGER      NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL
);

CREATE TABLE amenities (
    id         UUID PRIMARY KEY,
    code       VARCHAR(40) NOT NULL UNIQUE,
    name       VARCHAR(80) NOT NULL,
    icon       VARCHAR(40),
    category   VARCHAR(40) NOT NULL DEFAULT 'GENERAL',
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE rooms (
    id                UUID PRIMARY KEY,
    room_number       VARCHAR(20)    NOT NULL UNIQUE,
    room_type_id      UUID           NOT NULL REFERENCES room_types (id),
    name              VARCHAR(120)   NOT NULL,
    description       VARCHAR(4000),
    max_occupancy     SMALLINT       NOT NULL CHECK (max_occupancy BETWEEN 1 AND 12),
    bed_configuration VARCHAR(120)   NOT NULL,
    size_sq_m         SMALLINT       CHECK (size_sq_m > 0),
    floor             SMALLINT,
    view_type         VARCHAR(40),
    base_price        NUMERIC(12, 2) NOT NULL CHECK (base_price > 0),
    currency          VARCHAR(3)     NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    status            VARCHAR(20)    NOT NULL CHECK (status IN ('ACTIVE', 'MAINTENANCE', 'OUT_OF_SERVICE', 'INACTIVE')),
    status_reason     VARCHAR(255),
    created_at        TIMESTAMPTZ    NOT NULL,
    updated_at        TIMESTAMPTZ    NOT NULL,
    version           BIGINT         NOT NULL DEFAULT 0
);
CREATE INDEX ix_rooms_type ON rooms (room_type_id);
CREATE INDEX ix_rooms_status_price ON rooms (status, base_price);
CREATE INDEX ix_rooms_occupancy ON rooms (max_occupancy);

CREATE TABLE room_amenities (
    room_id    UUID NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    amenity_id UUID NOT NULL REFERENCES amenities (id),
    PRIMARY KEY (room_id, amenity_id)
);
CREATE INDEX ix_room_amenities_amenity ON room_amenities (amenity_id);

-- Image binaries live in the media store; the database keeps only metadata and an opaque storage key.
CREATE TABLE room_images (
    id           UUID PRIMARY KEY,
    room_id      UUID         NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    storage_key  VARCHAR(80)  NOT NULL UNIQUE,
    content_type VARCHAR(40)  NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp')),
    size_bytes   INTEGER      NOT NULL CHECK (size_bytes > 0),
    alt_text     VARCHAR(200),
    sort_order   INTEGER      NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_room_images_room ON room_images (room_id, sort_order);

-- One allocation per booking. Dates are hotel-local and the stay is the half-open interval
-- [check_in, check_out): the check-out day itself is free for the next guest.
CREATE TABLE inventory_allocations (
    id              UUID PRIMARY KEY,
    booking_id      UUID        NOT NULL,
    room_id         UUID        NOT NULL REFERENCES rooms (id),
    check_in        DATE        NOT NULL,
    check_out       DATE        NOT NULL,
    stay_range      DATERANGE   GENERATED ALWAYS AS (daterange(check_in, check_out, '[)')) STORED,
    guests          SMALLINT    NOT NULL CHECK (guests > 0),
    status          VARCHAR(20) NOT NULL CHECK (status IN ('HELD', 'CONFIRMED', 'RELEASED', 'EXPIRED')),
    hold_expires_at TIMESTAMPTZ,
    confirmed_at    TIMESTAMPTZ,
    released_at     TIMESTAMPTZ,
    release_reason  VARCHAR(80),
    price_quote     JSONB       NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    version         BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT ux_allocations_booking UNIQUE (booking_id),
    CONSTRAINT ck_allocations_dates CHECK (check_out > check_in),
    CONSTRAINT ck_allocations_hold_expiry CHECK (status <> 'HELD' OR hold_expires_at IS NOT NULL),
    -- THE double-booking guard: no two active allocations of one room may overlap. Enforced by the
    -- database, so it holds under any concurrency and for every code path.
    CONSTRAINT ex_allocations_no_overlap EXCLUDE USING gist (room_id WITH =, stay_range WITH &&)
        WHERE (status IN ('HELD', 'CONFIRMED'))
);
CREATE INDEX ix_allocations_room_range ON inventory_allocations USING gist (room_id, stay_range);
CREATE INDEX ix_allocations_held_expiry ON inventory_allocations (hold_expires_at) WHERE status = 'HELD';
CREATE INDEX ix_allocations_status_checkin ON inventory_allocations (status, check_in);

-- Append-only audit trail of inventory and catalogue changes.
CREATE TABLE inventory_events (
    id            BIGSERIAL PRIMARY KEY,
    room_id       UUID,
    allocation_id UUID,
    booking_id    UUID,
    event_type    VARCHAR(40)  NOT NULL,
    actor         VARCHAR(80)  NOT NULL,
    detail        VARCHAR(500),
    occurred_at   TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_inventory_events_room ON inventory_events (room_id, occurred_at DESC);
CREATE INDEX ix_inventory_events_booking ON inventory_events (booking_id);

-- Pricing policies are immutable versions; exactly one is ACTIVE. Quotes record the version used.
CREATE TABLE pricing_policies (
    id                 UUID PRIMARY KEY,
    version            INTEGER       NOT NULL UNIQUE CHECK (version > 0),
    status             VARCHAR(20)   NOT NULL CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    weekend_days       VARCHAR(80)   NOT NULL,
    weekend_multiplier NUMERIC(6, 4) NOT NULL CHECK (weekend_multiplier BETWEEN 0.5 AND 3),
    min_price_factor   NUMERIC(6, 4) NOT NULL CHECK (min_price_factor > 0),
    max_price_factor   NUMERIC(6, 4) NOT NULL,
    service_fee_rate   NUMERIC(6, 4) NOT NULL CHECK (service_fee_rate BETWEEN 0 AND 0.5),
    tax_rate           NUMERIC(6, 4) NOT NULL CHECK (tax_rate BETWEEN 0 AND 0.5),
    notes              VARCHAR(500),
    created_by         VARCHAR(80)   NOT NULL,
    created_at         TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_pricing_factor_range CHECK (max_price_factor >= min_price_factor)
);
CREATE UNIQUE INDEX ux_pricing_single_active ON pricing_policies (status) WHERE status = 'ACTIVE';

CREATE TABLE pricing_seasons (
    id         UUID PRIMARY KEY,
    policy_id  UUID          NOT NULL REFERENCES pricing_policies (id) ON DELETE CASCADE,
    name       VARCHAR(80)   NOT NULL,
    start_date DATE          NOT NULL,
    end_date   DATE          NOT NULL,
    multiplier NUMERIC(6, 4) NOT NULL CHECK (multiplier BETWEEN 0.5 AND 3),
    CONSTRAINT ck_season_dates CHECK (end_date >= start_date)
);
CREATE INDEX ix_pricing_seasons_policy ON pricing_seasons (policy_id);

-- Demand tiers use the hotel's own occupancy for the room type on each night (not external market data).
CREATE TABLE pricing_demand_tiers (
    id            UUID PRIMARY KEY,
    policy_id     UUID          NOT NULL REFERENCES pricing_policies (id) ON DELETE CASCADE,
    min_occupancy NUMERIC(5, 4) NOT NULL CHECK (min_occupancy BETWEEN 0 AND 1),
    multiplier    NUMERIC(6, 4) NOT NULL CHECK (multiplier BETWEEN 0.5 AND 3),
    CONSTRAINT ux_demand_tier UNIQUE (policy_id, min_occupancy)
);
