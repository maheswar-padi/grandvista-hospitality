package com.grandvista.booking.client;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** Client for payment-service's internal API. Creation is idempotent per bookingId; refunds per key. */
public class PaymentServiceClient {

    public static final String SERVICE = "payment-service";

    public record CreatePayment(UUID bookingId, String bookingReference, UUID customerId, BigDecimal amount,
                                String currency, Instant expiresAt) {
    }

    public record Payment(UUID id, UUID bookingId, BigDecimal amount, String currency, String status, String provider,
                          boolean simulated, String failureCode, BigDecimal refundedAmount, Instant expiresAt) {
    }

    public record RefundRequest(BigDecimal amount, String reason, String idempotencyKey) {
    }

    public record Refund(UUID id, UUID paymentId, BigDecimal amount, String status, String failureMessage) {
    }

    private final RestClient http;
    private final Resilience resilience;

    public PaymentServiceClient(RestClient http, Resilience resilience) {
        this.http = http;
        this.resilience = resilience;
    }

    public Payment create(CreatePayment request) {
        return RemoteCalls.call(SERVICE, "create", resilience, () -> http.post().uri("/internal/v1/payments")
                .contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(Payment.class));
    }

    public Payment getByBooking(UUID bookingId) {
        return RemoteCalls.call(SERVICE, "get", resilience, () -> http.get()
                .uri("/internal/v1/payments/by-booking/{bookingId}", bookingId).retrieve().body(Payment.class));
    }

    public Payment cancel(UUID paymentId, String reason) {
        return RemoteCalls.call(SERVICE, "cancel", resilience, () -> http.post()
                .uri("/internal/v1/payments/{paymentId}/cancel", paymentId)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("reason", reason)).retrieve().body(Payment.class));
    }

    public Refund refund(UUID paymentId, RefundRequest request) {
        return RemoteCalls.call(SERVICE, "refund", resilience, () -> http.post()
                .uri("/internal/v1/payments/{paymentId}/refunds", paymentId)
                .contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(Refund.class));
    }
}
