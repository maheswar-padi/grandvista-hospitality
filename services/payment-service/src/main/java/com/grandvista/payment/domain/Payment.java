package com.grandvista.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A payment intent for one booking. The amount is fixed at creation from the booking's price snapshot. */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    private UUID id;

    @Column(name = "booking_id", nullable = false, unique = true)
    private UUID bookingId;

    @Column(name = "booking_reference", nullable = false)
    private String bookingReference;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(nullable = false)
    private String provider;

    @Column(name = "provider_reference")
    private String providerReference;

    @Column(name = "provider_order_id")
    private String providerOrderId;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "failure_message")
    private String failureMessage;

    @Column(name = "refunded_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal refundedAmount = BigDecimal.ZERO;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Payment() {
    }

    public Payment(UUID bookingId, String bookingReference, UUID customerId, BigDecimal amount, String currency,
            String provider, Instant expiresAt, Instant now) {
        this.id = UUID.randomUUID();
        this.bookingId = bookingId;
        this.bookingReference = bookingReference;
        this.customerId = customerId;
        this.amount = amount;
        this.currency = currency;
        this.provider = provider;
        this.status = PaymentStatus.CREATED;
        this.expiresAt = expiresAt;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** @return the previous status */
    public PaymentStatus transitionTo(PaymentStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalPaymentTransitionException(status, target);
        }
        PaymentStatus previous = status;
        status = target;
        updatedAt = now;
        return previous;
    }

    /** Records the provider order once; a different order id for the same payment is refused. */
    public boolean attachProviderOrder(String orderId) {
        if (providerOrderId == null) {
            providerOrderId = orderId;
            return true;
        }
        return providerOrderId.equals(orderId);
    }

    public String getProviderOrderId() {
        return providerOrderId;
    }

    public void recordProviderReference(String reference) {
        if (reference != null) {
            this.providerReference = reference;
        }
    }

    public void recordFailure(String code, String message) {
        this.failureCode = code;
        this.failureMessage = message;
    }

    public void addRefunded(BigDecimal refund) {
        this.refundedAmount = refundedAmount.add(refund);
    }

    public BigDecimal refundableAmount() {
        return amount.subtract(refundedAmount);
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public UUID getId() {
        return id;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public String getBookingReference() {
        return bookingReference;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public BigDecimal getRefundedAmount() {
        return refundedAmount;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
