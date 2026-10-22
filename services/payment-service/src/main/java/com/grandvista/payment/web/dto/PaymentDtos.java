package com.grandvista.payment.web.dto;

import com.grandvista.payment.domain.Payment;
import com.grandvista.payment.domain.PaymentEvent;
import com.grandvista.payment.domain.PaymentStatus;
import com.grandvista.payment.domain.Refund;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PaymentDtos {

    private PaymentDtos() {
    }

    /** Internal: created by the booking-service with the amount from the booking's price snapshot. */
    public record CreatePaymentRequest(
            @NotNull UUID bookingId,
            @NotBlank @Pattern(regexp = "^[A-Z0-9-]{4,20}$") String bookingReference,
            @NotNull UUID customerId,
            @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @NotNull @Pattern(regexp = "^[A-Z]{3}$") String currency,
            @NotNull @Future Instant expiresAt) {
    }

    /**
     * Customer confirmation. Carries either a provider-issued payment-method token (simulated provider) or the
     * Razorpay Checkout result (payment id, order id, signature) — never card numbers — and no amount: the amount
     * was fixed by the booking-service and cannot be changed by the client.
     */
    public record ConfirmPaymentRequest(
            @Pattern(regexp = "^[a-z0-9_]{3,40}$", message = "must be a provider payment-method token")
            String paymentMethodToken,
            @Pattern(regexp = "^pay_[A-Za-z0-9]{6,40}$") String razorpayPaymentId,
            @Pattern(regexp = "^order_[A-Za-z0-9]{6,40}$") String razorpayOrderId,
            @Pattern(regexp = "^[a-fA-F0-9]{64}$") String razorpaySignature) {

        public ConfirmPaymentRequest(String paymentMethodToken) {
            this(paymentMethodToken, null, null, null);
        }

        @jakarta.validation.constraints.AssertTrue(message = "send either paymentMethodToken or all three razorpay fields")
        public boolean isOneMethod() {
            boolean token = paymentMethodToken != null;
            boolean rzpAll = razorpayPaymentId != null && razorpayOrderId != null && razorpaySignature != null;
            boolean rzpAny = razorpayPaymentId != null || razorpayOrderId != null || razorpaySignature != null;
            return token ? !rzpAny : rzpAll;
        }

        public com.grandvista.payment.provider.PaymentProvider.CheckoutResult checkoutResult() {
            return razorpayPaymentId == null ? null
                    : new com.grandvista.payment.provider.PaymentProvider.CheckoutResult(razorpayPaymentId, razorpayOrderId,
                    razorpaySignature);
        }
    }

    /**
     * What the browser needs to pay. For {@code RAZORPAY}: the publishable key id and the order to open in Razorpay
     * Checkout (test mode). For {@code SIMULATED}: only the provider name (use the test payment methods).
     */
    public record CheckoutResponse(UUID paymentId, String provider, boolean testMode, String keyId, String orderId,
                                   BigDecimal amount, Long amountMinorUnits, String currency, String merchantName,
                                   String description, String bookingReference, String prefillEmail, Instant expiresAt) {
    }

    public record CancelPaymentRequest(@NotBlank @Pattern(regexp = "^[A-Z_]{3,60}$") String reason) {
    }

    public record RefundRequest(
            @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @NotBlank @Pattern(regexp = "^[A-Z_]{3,60}$") String reason,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9:_-]{8,80}$") String idempotencyKey) {
    }

    public record PaymentResponse(UUID id, UUID bookingId, String bookingReference, UUID customerId, BigDecimal amount,
                                  String currency, PaymentStatus status, String provider, boolean simulated,
                                  String providerReference, String failureCode, String failureMessage,
                                  BigDecimal refundedAmount, Instant expiresAt, Instant createdAt, Instant updatedAt) {
        public static PaymentResponse from(Payment p) {
            return new PaymentResponse(p.getId(), p.getBookingId(), p.getBookingReference(), p.getCustomerId(),
                    p.getAmount(), p.getCurrency(), p.getStatus(), p.getProvider(), "SIMULATED".equals(p.getProvider()),
                    p.getProviderReference(), p.getFailureCode(), p.getFailureMessage(), p.getRefundedAmount(),
                    p.getExpiresAt(), p.getCreatedAt(), p.getUpdatedAt());
        }
    }

    public record RefundResponse(UUID id, UUID paymentId, BigDecimal amount, Refund.Status status, String reason,
                                 String providerReference, String failureMessage, Instant createdAt) {
        public static RefundResponse from(Refund r) {
            return new RefundResponse(r.getId(), r.getPaymentId(), r.getAmount(), r.getStatus(), r.getReason(),
                    r.getProviderReference(), r.getFailureMessage(), r.getCreatedAt());
        }
    }

    public record PaymentEventResponse(Long id, String eventType, String fromStatus, String toStatus, String actor,
                                       String detail, Instant occurredAt) {
        public static PaymentEventResponse from(PaymentEvent e) {
            return new PaymentEventResponse(e.getId(), e.getEventType(), e.getFromStatus(), e.getToStatus(), e.getActor(),
                    e.getDetail(), e.getOccurredAt());
        }
    }

    public record PaymentDetailResponse(PaymentResponse payment, List<RefundResponse> refunds,
                                        List<PaymentEventResponse> events) {
    }

    public record TestPaymentMethod(String token, String label, String expectedOutcome) {
    }

    public record StatusTotal(PaymentStatus status, long count, BigDecimal amount, BigDecimal refunded) {
    }

    public record PaymentSummary(List<StatusTotal> byStatus, BigDecimal capturedAmount, BigDecimal refundedAmount,
                                 long outboxPending, long outboxDead) {
    }
}
