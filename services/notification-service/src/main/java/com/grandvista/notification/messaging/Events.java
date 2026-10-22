package com.grandvista.notification.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Consumer-side views of the published event contracts (schemaVersion 1; unknown fields are ignored). */
public final class Events {

    private Events() {
    }

    public record BookingEvent(UUID eventId, String eventType, int schemaVersion, UUID bookingId, String reference,
                               UUID customerId, String customerEmail, String guestName, String roomName,
                               String roomTypeName, String roomNumber, LocalDate checkIn, LocalDate checkOut,
                               Integer nights, Integer guests, BigDecimal totalAmount, String currency,
                               BigDecimal refundAmount, Instant holdExpiresAt, String status, String reason,
                               Instant occurredAt) {
    }

    public record PaymentEvent(UUID eventId, String eventType, int schemaVersion, UUID paymentId, UUID bookingId,
                               String status, BigDecimal amount, String currency, String failureCode, String provider,
                               Instant occurredAt) {
    }
}
