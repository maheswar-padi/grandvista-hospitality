package com.grandvista.booking.client;

import com.grandvista.platform.client.Retrier;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Client for room-service's internal inventory API ({@code /internal/v1/holds}). Every operation is
 * idempotent per bookingId on the room-service side, which is what makes the bounded retries safe.
 * The DTOs below are this service's own view of the contract (no shared entity classes).
 */
public class RoomServiceClient {

    public static final String SERVICE = "room-service";

    public record HoldRequest(UUID bookingId, UUID roomId, LocalDate checkIn, LocalDate checkOut, int guests) {
    }

    public record NightlyRate(LocalDate date, BigDecimal baseRate, BigDecimal dayMultiplier, String seasonName,
                              BigDecimal seasonMultiplier, BigDecimal occupancy, BigDecimal demandMultiplier,
                              String limitApplied, BigDecimal rate) {
    }

    public record Quote(String currency, LocalDate checkIn, LocalDate checkOut, int nights, List<NightlyRate> nightlyRates,
                        BigDecimal subtotal, BigDecimal serviceFee, BigDecimal taxes, BigDecimal total,
                        BigDecimal averageNightlyRate, int pricingPolicyVersion, Instant quotedAt) {
    }

    public record Hold(UUID allocationId, UUID bookingId, UUID roomId, String roomNumber, String roomName,
                       String roomTypeName, LocalDate checkIn, LocalDate checkOut, int guests, String status,
                       Instant holdExpiresAt, Instant confirmedAt, Quote quote) {
    }

    private final RestClient http;
    private final Retrier retrier;

    public RoomServiceClient(RestClient http, Retrier retrier) {
        this.http = http;
        this.retrier = retrier;
    }

    public Hold hold(HoldRequest request) {
        return RemoteCalls.call(SERVICE, "hold", retrier, () -> http.post().uri("/internal/v1/holds")
                .contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(Hold.class));
    }

    public Hold confirm(UUID bookingId) {
        return RemoteCalls.call(SERVICE, "confirm", retrier, () -> http.post()
                .uri("/internal/v1/holds/{bookingId}/confirm", bookingId).retrieve().body(Hold.class));
    }

    public void release(UUID bookingId, String reason) {
        RemoteCalls.call(SERVICE, "release", retrier, () -> http.post()
                .uri("/internal/v1/holds/{bookingId}/release", bookingId)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("reason", reason))
                .retrieve().toBodilessEntity());
    }
}
