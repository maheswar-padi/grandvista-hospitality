package com.grandvista.booking.client;

import com.grandvista.platform.client.Retrier;
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
    private final Retrier retrier;

    public PaymentServiceClient(RestClient http, Retrier retrier) {
        this.http = http;
        this.retrier = retrier;
    }

    public Payment create(CreatePayment request) {
        return RemoteCalls.call(SERVICE, "create", retrier, () -> http.post().uri("/internal/v1/payments")
                .contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(Payment.class));
    }

    public Payment getByBooking(UUID bookingId) {
        return RemoteCalls.call(SERVICE, "get", retrier, () -> http.get()
                .uri("/internal/v1/payments/by-booking/{bookingId}", bookingId).retrieve().body(Payment.class));
    }

    public Payment cancel(UUID paymentId, String reason) {
        return RemoteCalls.call(SERVICE, "cancel", retrier, () -> http.post()
                .uri("/internal/v1/payments/{paymentId}/cancel", paymentId)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("reason", reason)).retrieve().body(Payment.class));
    }

    public Refund refund(UUID paymentId, RefundRequest request) {
        return RemoteCalls.call(SERVICE, "refund", retrier, () -> http.post()
                .uri("/internal/v1/payments/{paymentId}/refunds", paymentId)
                .contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(Refund.class));
    }
}
