package com.grandvista.payment.provider.simulated;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** The SIMULATED provider's own record of a charge (stands in for state held by an external provider). */
@Entity
@Table(schema = "simulated_provider", name = "charges")
public class SimulatedCharge {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String reference;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "payment_method", nullable = false)
    private String paymentMethod;

    @Column(nullable = false)
    private String status;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "final_status", nullable = false)
    private String finalStatus;

    @Column(name = "webhook_due_at")
    private Instant webhookDueAt;

    @Column(name = "webhook_sent_at")
    private Instant webhookSentAt;

    @Column(name = "webhook_attempts", nullable = false)
    private int webhookAttempts;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SimulatedCharge() {
    }

    SimulatedCharge(String idempotencyKey, BigDecimal amount, String currency, String paymentMethod, String status,
            String finalStatus, String failureCode, Instant webhookDueAt, Instant now) {
        this.id = UUID.randomUUID();
        this.reference = "sim_ch_" + id.toString().replace("-", "");
        this.idempotencyKey = idempotencyKey;
        this.amount = amount;
        this.currency = currency;
        this.paymentMethod = paymentMethod;
        this.status = status;
        this.finalStatus = finalStatus;
        this.failureCode = failureCode;
        this.webhookDueAt = webhookDueAt;
        this.createdAt = now;
    }

    void settle() {
        this.status = finalStatus;
    }

    void markWebhookSent(Instant now) {
        webhookSentAt = now;
        webhookAttempts++;
    }

    void markWebhookFailed(Instant retryAt) {
        webhookAttempts++;
        webhookDueAt = retryAt;
    }

    public UUID getId() {
        return id;
    }

    public String getReference() {
        return reference;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getStatus() {
        return status;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public int getWebhookAttempts() {
        return webhookAttempts;
    }
}
