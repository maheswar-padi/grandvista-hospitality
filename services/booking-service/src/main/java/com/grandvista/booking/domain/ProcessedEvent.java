package com.grandvista.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Inbox record: an integration event that has been applied and must not be applied again. */
@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedEvent() {
    }

    public ProcessedEvent(UUID eventId, String eventType, UUID bookingId, Instant processedAt) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.bookingId = bookingId;
        this.processedAt = processedAt;
    }
}
