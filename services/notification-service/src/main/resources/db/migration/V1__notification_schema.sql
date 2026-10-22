-- notification-service owns its read model of booking contacts and the log of e-mails sent.

-- Built from booking events (event-carried state transfer); never read by any other service.
CREATE TABLE booking_contacts (
    booking_id      UUID PRIMARY KEY,
    reference       VARCHAR(20)   NOT NULL,
    customer_email  VARCHAR(320)  NOT NULL,
    guest_name      VARCHAR(200),
    room_name       VARCHAR(200),
    room_type_name  VARCHAR(120),
    check_in        DATE,
    check_out       DATE,
    nights          INTEGER,
    guests          INTEGER,
    total_amount    NUMERIC(12, 2),
    currency        VARCHAR(3),
    last_event_at   TIMESTAMPTZ   NOT NULL
);

-- One row per e-mail. UNIQUE (event_id, notification_type) is the duplicate-e-mail guard.
CREATE TABLE notifications (
    id                 UUID PRIMARY KEY,
    event_id           UUID          NOT NULL,
    notification_type  VARCHAR(40)   NOT NULL,
    booking_id         UUID,
    booking_reference  VARCHAR(20),
    recipient          VARCHAR(320)  NOT NULL,
    subject            VARCHAR(300)  NOT NULL,
    status             VARCHAR(20)   NOT NULL CHECK (status IN ('SENDING', 'SENT')),
    created_at         TIMESTAMPTZ   NOT NULL,
    sent_at            TIMESTAMPTZ,
    CONSTRAINT ux_notifications_event_type UNIQUE (event_id, notification_type)
);
CREATE INDEX ix_notifications_booking ON notifications (booking_id, created_at DESC);
CREATE INDEX ix_notifications_created ON notifications (created_at DESC);
