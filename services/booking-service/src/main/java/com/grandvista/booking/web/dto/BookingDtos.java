package com.grandvista.booking.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.grandvista.booking.domain.BookingStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class BookingDtos {

    private BookingDtos() {
    }

    public record GuestDetails(
            @NotBlank @Size(min = 2, max = 120) String fullName,
            @NotBlank @Email @Size(max = 254) String email,
            @Pattern(regexp = "^\\+?[0-9 ()-]{6,32}$", message = "must be a valid phone number") String phone) {
    }

    /**
     * Booking request. There is no price field the server trusts: {@code expectedTotal} is only compared
     * with the authoritative price so the customer is never charged an amount they did not see.
     */
    public record CreateBookingRequest(
            @NotNull UUID roomId,
            @NotNull LocalDate checkIn,
            @NotNull LocalDate checkOut,
            @NotNull @Min(1) @Max(12) Integer guests,
            @NotNull @Valid GuestDetails primaryGuest,
            @Size(max = 500) String specialRequests,
            @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal expectedTotal) {
    }

    public record CancelRequest(@Size(max = 200) String reason) {
    }

    public record RoomSummary(UUID id, String roomNumber, String name, String typeName) {
    }

    public record GuestResponse(String fullName, String email, String phone) {
    }

    public record PriceResponse(int nights, BigDecimal subtotal, BigDecimal serviceFee, BigDecimal taxes, BigDecimal total,
                                String currency, int pricingPolicyVersion, Instant quotedAt, JsonNode nightlyRates) {
    }

    public record BookingResponse(UUID id, String reference, BookingStatus status, String statusReason, RoomSummary room,
                                  LocalDate checkIn, LocalDate checkOut, int nights, int guests, GuestResponse primaryGuest,
                                  String specialRequests, PriceResponse price, UUID paymentId, Instant holdExpiresAt,
                                  BigDecimal refundAmount, List<String> allowedActions, Instant createdAt,
                                  Instant confirmedAt, Instant cancelledAt, UUID customerId, String customerEmail) {
    }

    public record BookingStatusResponse(UUID id, String reference, BookingStatus status, String statusReason,
                                        UUID paymentId, Instant holdExpiresAt, Instant updatedAt) {
    }

    public record HistoryEntry(BookingStatus fromStatus, BookingStatus toStatus, String reason, String actor,
                               Instant occurredAt) {
    }

    public record CancellationQuote(UUID bookingId, boolean allowed, int refundPercent, BigDecimal refundAmount,
                                    String currency, long daysBeforeCheckIn, String rule) {
    }

    /** Versioned payment event contract published by the payment-service outbox. */
    /** Consumers ignore unknown fields so producers can add fields without a new schema version. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentEventMessage(
            @NotNull UUID eventId,
            @NotBlank @Pattern(regexp = "^[A-Z_]{3,40}$") String eventType,
            int schemaVersion,
            @NotNull UUID paymentId,
            @NotNull UUID bookingId,
            String status,
            BigDecimal amount,
            String currency,
            String failureCode,
            Instant occurredAt) {
    }

    public record StatusCount(BookingStatus status, long count, BigDecimal amount) {
    }

    public record DailyCount(LocalDate day, long created, long confirmed) {
    }

    public record BookingSummary(List<StatusCount> byStatus, BigDecimal confirmedRevenue, long arrivalsToday,
                                 long needsAttention, List<DailyCount> last30Days) {
    }
}
