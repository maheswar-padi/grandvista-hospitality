package com.grandvista.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Append-only audit trail for a payment. */
@Entity
@Table(name = "payment_events")
public class PaymentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "from_status")
    private String fromStatus;

    @Column(name = "to_status")
    private String toStatus;

    @Column(nullable = false)
    private String actor;

    private String detail;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected PaymentEvent() {
    }

    public PaymentEvent(UUID paymentId, String eventType, PaymentStatus from, PaymentStatus to, String actor,
            String detail, Instant occurredAt) {
        this.paymentId = paymentId;
        this.eventType = eventType;
        this.fromStatus = from == null ? null : from.name();
        this.toStatus = to == null ? null : to.name();
        this.actor = actor;
        this.detail = detail == null || detail.length() <= 500 ? detail : detail.substring(0, 500);
        this.occurredAt = occurredAt;
    }

    public Long getId() {
        return id;
    }

    public String getEventType() {
        return eventType;
    }

    public String getFromStatus() {
        return fromStatus;
    }

    public String getToStatus() {
        return toStatus;
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
