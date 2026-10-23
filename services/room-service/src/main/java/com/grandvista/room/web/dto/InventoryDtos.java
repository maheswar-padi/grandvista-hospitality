package com.grandvista.room.web.dto;

import com.grandvista.room.domain.AllocationStatus;
import com.grandvista.room.domain.InventoryAllocation;
import com.grandvista.room.domain.InventoryEvent;
import com.grandvista.room.pricing.PriceQuote;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Internal inventory contract used by the booking-service (versioned under {@code /internal/v1}).
 * The bookingId is the idempotency key: repeating a request with the same bookingId is safe.
 */
public final class InventoryDtos {

    private InventoryDtos() {
    }

    public record HoldRequest(@NotNull UUID bookingId, @NotNull UUID roomId, @NotNull LocalDate checkIn,
                              @NotNull LocalDate checkOut, @NotNull @Min(1) @Max(12) Integer guests) {
    }

    public record ReleaseRequest(@NotNull @Pattern(regexp = "^[A-Z_]{3,60}$") String reason) {
    }

    public record HoldResponse(UUID allocationId, UUID bookingId, UUID propertyId, String propertyName, String propertyCity,
                               String propertyTimeZone, UUID roomId, String roomNumber, String roomName,
                               String roomTypeName, LocalDate checkIn, LocalDate checkOut, int guests,
                               AllocationStatus status, Instant holdExpiresAt, Instant confirmedAt, PriceQuote quote) {
    }

    public record AllocationResponse(UUID id, UUID bookingId, UUID roomId, LocalDate checkIn, LocalDate checkOut,
                                     int guests, AllocationStatus status, Instant holdExpiresAt, Instant confirmedAt,
                                     Instant releasedAt, String releaseReason, Instant createdAt) {
        public static AllocationResponse from(InventoryAllocation a) {
            return new AllocationResponse(a.getId(), a.getBookingId(), a.getRoomId(), a.getCheckIn(), a.getCheckOut(),
                    a.getGuests(), a.getStatus(), a.getHoldExpiresAt(), a.getConfirmedAt(), a.getReleasedAt(),
                    a.getReleaseReason(), a.getCreatedAt());
        }
    }

    public record InventoryEventResponse(Long id, UUID roomId, UUID allocationId, UUID bookingId, String eventType,
                                         String actor, String detail, Instant occurredAt) {
        public static InventoryEventResponse from(InventoryEvent e) {
            return new InventoryEventResponse(e.getId(), e.getRoomId(), e.getAllocationId(), e.getBookingId(),
                    e.getEventType(), e.getActor(), e.getDetail(), e.getOccurredAt());
        }
    }

    public record OccupancyPoint(LocalDate date, long allocatedRooms, long sellableRooms, double occupancy) {
    }

    public record InventorySummary(long totalRooms, long activeRooms, long maintenanceRooms, long outOfServiceRooms,
                                   long inactiveRooms, double occupancyToday, long arrivalsToday, long departuresToday) {
    }
}
