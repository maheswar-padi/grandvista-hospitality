package com.grandvista.room.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Append-only audit record of inventory and catalogue changes. */
@Entity
@Table(name = "inventory_events")
public class InventoryEvent {

    public enum Type {
        ROOM_CREATED, ROOM_UPDATED, ROOM_STATUS_CHANGED, ROOM_IMAGE_ADDED, ROOM_IMAGE_REMOVED,
        HOLD_CREATED, HOLD_CONFIRMED, HOLD_REACQUIRED, HOLD_RELEASED, HOLD_EXPIRED, PRICING_POLICY_PUBLISHED,
        PROPERTY_CREATED, PROPERTY_UPDATED, RECONCILIATION_RUN, RECONCILIATION_FINDING, RECONCILIATION_RELEASED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_id")
    private UUID roomId;

    @Column(name = "allocation_id")
    private UUID allocationId;

    @Column(name = "booking_id")
    private UUID bookingId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(nullable = false)
    private String actor;

    private String detail;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected InventoryEvent() {
    }

    public InventoryEvent(UUID roomId, UUID allocationId, UUID bookingId, Type type, String actor, String detail,
            Instant occurredAt) {
        this.roomId = roomId;
        this.allocationId = allocationId;
        this.bookingId = bookingId;
        this.eventType = type.name();
        this.actor = actor;
        this.detail = detail == null || detail.length() <= 500 ? detail : detail.substring(0, 500);
        this.occurredAt = occurredAt;
    }

    public Long getId() {
        return id;
    }

    public UUID getRoomId() {
        return roomId;
    }

    public UUID getAllocationId() {
        return allocationId;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getActor() {
        return actor;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
