-- Multi-property portfolio: e-mails name the hotel (property) of the booking.
ALTER TABLE booking_contacts ADD COLUMN property_name VARCHAR(120);
