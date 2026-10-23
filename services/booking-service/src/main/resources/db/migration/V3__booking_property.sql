-- Multi-property portfolio: a booking records the property (hotel) of its room, copied from the room-service
-- hold. Bookings made before the portfolio existed keep NULL (their rooms belong to the original hotel).
ALTER TABLE bookings ADD COLUMN property_id UUID;
ALTER TABLE bookings ADD COLUMN property_name VARCHAR(120);
CREATE INDEX ix_bookings_property ON bookings (property_id, check_in);
