package com.grandvista.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    public enum Status { PENDING, SENT, DEAD }

    @Id
    private UUID id;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "correlation_id")
    private String correlationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected OutboxEvent() {
    }

    public OutboxEvent(UUID id, UUID aggregateId, String eventType, String payload, String correlationId, Instant now) {
        this.id = id;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.correlationId = correlationId;
        this.status = Status.PENDING;
        this.nextAttemptAt = now;
        this.createdAt = now;
    }

    public void markSent(Instant now) {
        status = Status.SENT;
        sentAt = now;
        attempts++;
        lastError = null;
    }

    /** Exponential backoff capped at {@code maxBackoff}; dead-lettered after {@code maxAttempts}. */
    public void markFailed(String error, int maxAttempts, Duration maxBackoff, Instant now) {
        attempts++;
        lastError = error == null ? null : error.substring(0, Math.min(500, error.length()));
        if (attempts >= maxAttempts) {
            status = Status.DEAD;
            return;
        }
        long seconds = Math.min(maxBackoff.toSeconds(), (long) Math.pow(2, Math.min(attempts, 20)));
        nextAttemptAt = now.plusSeconds(seconds);
    }

    public void markDead(String error) {
        status = Status.DEAD;
        attempts++;
        lastError = error == null ? null : error.substring(0, Math.min(500, error.length()));
    }

    public UUID getId() {
        return id;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Status getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }
}
