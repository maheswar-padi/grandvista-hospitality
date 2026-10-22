package com.grandvista.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_attempts")
public class PaymentAttempt {

    public enum Status { PROCESSING, SUCCEEDED, FAILED, PENDING_PROVIDER, UNKNOWN, REJECTED }

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "payment_method", nullable = false)
    private String paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "provider_reference")
    private String providerReference;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaymentAttempt() {
    }

    public PaymentAttempt(UUID paymentId, String idempotencyKey, String paymentMethod, Status status, Instant now) {
        this.id = UUID.randomUUID();
        this.paymentId = paymentId;
        this.idempotencyKey = idempotencyKey;
        this.paymentMethod = paymentMethod;
        this.status = status;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void complete(Status status, String providerReference, String failureCode, Instant now) {
        this.status = status;
        this.providerReference = providerReference;
        this.failureCode = failureCode;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public Status getStatus() {
        return status;
    }

    public String getPaymentMethod() {
        return paymentMethod;
    }
}
