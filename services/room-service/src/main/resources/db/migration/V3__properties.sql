-- Multi-property portfolio: every room type, room and pricing policy belongs to one property (hotel).
-- The hotel that existed before this migration becomes property HYD; its data is attached to it.

CREATE TABLE properties (
    id         UUID PRIMARY KEY,
    code       VARCHAR(10)  NOT NULL UNIQUE CHECK (code ~ '^[A-Z0-9]{2,10}$'),
    name       VARCHAR(120) NOT NULL,
    city       VARCHAR(80)  NOT NULL,
    country    VARCHAR(2)   NOT NULL CHECK (country ~ '^[A-Z]{2}$'),
    time_zone  VARCHAR(60)  NOT NULL,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL,
    version    BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX ix_properties_city ON properties (lower(city));

INSERT INTO properties (id, code, name, city, country, time_zone, active, created_at, updated_at)
VALUES ('d1000000-0000-4000-8000-000000000001', 'HYD', 'GrandVista Hyderabad', 'Hyderabad', 'IN', 'Asia/Kolkata',
        TRUE, now(), now());

-- Room types: owned by a property; codes are unique within a property.
ALTER TABLE room_types ADD COLUMN property_id UUID;
UPDATE room_types SET property_id = 'd1000000-0000-4000-8000-000000000001';
ALTER TABLE room_types ALTER COLUMN property_id SET NOT NULL;
ALTER TABLE room_types ADD CONSTRAINT fk_room_types_property FOREIGN KEY (property_id) REFERENCES properties (id);
ALTER TABLE room_types DROP CONSTRAINT room_types_code_key;
ALTER TABLE room_types ADD CONSTRAINT ux_room_types_property_code UNIQUE (property_id, code);
-- Target of the composite foreign key below.
ALTER TABLE room_types ADD CONSTRAINT ux_room_types_id_property UNIQUE (id, property_id);

-- Rooms: the property is stored for filtering and must equal the room type's property (composite FK).
ALTER TABLE rooms ADD COLUMN property_id UUID;
UPDATE rooms r SET property_id = t.property_id FROM room_types t WHERE t.id = r.room_type_id;
ALTER TABLE rooms ALTER COLUMN property_id SET NOT NULL;
ALTER TABLE rooms ADD CONSTRAINT fk_rooms_type_same_property FOREIGN KEY (room_type_id, property_id)
    REFERENCES room_types (id, property_id);
ALTER TABLE rooms DROP CONSTRAINT rooms_room_number_key;
ALTER TABLE rooms ADD CONSTRAINT ux_rooms_property_number UNIQUE (property_id, room_number);
CREATE INDEX ix_rooms_property_status ON rooms (property_id, status);

-- Pricing: one version history and exactly one ACTIVE policy per property.
ALTER TABLE pricing_policies ADD COLUMN property_id UUID;
UPDATE pricing_policies SET property_id = 'd1000000-0000-4000-8000-000000000001';
ALTER TABLE pricing_policies ALTER COLUMN property_id SET NOT NULL;
ALTER TABLE pricing_policies ADD CONSTRAINT fk_pricing_policies_property FOREIGN KEY (property_id) REFERENCES properties (id);
ALTER TABLE pricing_policies DROP CONSTRAINT pricing_policies_version_key;
ALTER TABLE pricing_policies ADD CONSTRAINT ux_pricing_policies_property_version UNIQUE (property_id, version);
DROP INDEX ux_pricing_single_active;
CREATE UNIQUE INDEX ux_pricing_single_active_per_property ON pricing_policies (property_id) WHERE status = 'ACTIVE';
