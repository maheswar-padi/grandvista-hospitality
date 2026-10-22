package com.grandvista.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refunds")
public class Refund {

    public enum Status { PENDING, SUCCEEDED, FAILED }

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private String reason;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "provider_reference")
    private String providerReference;

    @Column(name = "failure_message")
    private String failureMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Refund() {
    }

    public Refund(UUID paymentId, BigDecimal amount, String reason, String idempotencyKey, Instant now) {
        this.id = UUID.randomUUID();
        this.paymentId = paymentId;
        this.amount = amount;
        this.reason = reason;
        this.idempotencyKey = idempotencyKey;
        this.status = Status.PENDING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void succeed(String providerReference, Instant now) {
        this.status = Status.SUCCEEDED;
        this.providerReference = providerReference;
        this.updatedAt = now;
    }

    public void fail(String message, Instant now) {
        this.status = Status.FAILED;
        this.failureMessage = message;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Status getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
