package com.grandvista.booking.web;

import com.grandvista.booking.domain.Booking;
import com.grandvista.booking.domain.BookingStatus;
import com.grandvista.booking.repository.BookingRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only booking facts for the room-service's inventory reconciliation (SERVICE role only; never routed by the
 * gateway). Returns only what reconciliation needs — no guest or payment data.
 */
@RestController
@RequestMapping("/internal/v1/bookings")
@Tag(name = "Internal: booking lookups")
public class InternalBookingLookupController {

    /** Statuses in which a booking must have a room allocation (HELD while unpaid, CONFIRMED once paid). */
    static final EnumSet<BookingStatus> HOLDING_INVENTORY = EnumSet.of(BookingStatus.PENDING_PAYMENT, BookingStatus.CONFIRMED);

    public record LookupRequest(@NotNull @Size(max = 1000) List<UUID> bookingIds) {
    }

    public record ActiveRequest(@NotNull @Size(max = 1000) List<UUID> roomIds, @NotNull LocalDate checkOutFrom) {
    }

    public record BookingFact(UUID bookingId, String reference, BookingStatus status, UUID roomId, UUID propertyId,
                              LocalDate checkIn, LocalDate checkOut, Instant holdExpiresAt, Instant updatedAt) {
        static BookingFact from(Booking b) {
            return new BookingFact(b.getId(), b.getReference(), b.getStatus(), b.getRoomId(), b.getPropertyId(), b.getCheckIn(),
                    b.getCheckOut(), b.getHoldExpiresAt(), b.getUpdatedAt());
        }
    }

    private final BookingRepository bookings;

    public InternalBookingLookupController(BookingRepository bookings) {
        this.bookings = bookings;
    }

    @Operation(summary = "Status of the given bookings; unknown ids are simply absent from the answer")
    @PostMapping("/lookup")
    @Transactional(readOnly = true)
    public List<BookingFact> lookup(@Valid @RequestBody LookupRequest request) {
        return bookings.findAllById(request.bookingIds()).stream().map(BookingFact::from).toList();
    }

    @Operation(summary = "Bookings that should hold inventory (PENDING_PAYMENT, CONFIRMED) on the given rooms")
    @PostMapping("/holding-inventory")
    @Transactional(readOnly = true)
    public List<BookingFact> holdingInventory(@Valid @RequestBody ActiveRequest request) {
        if (request.roomIds().isEmpty()) {
            return List.of();
        }
        return bookings.findHoldingInventory(request.roomIds(), HOLDING_INVENTORY, request.checkOutFrom()).stream()
                .map(BookingFact::from).toList();
    }
}
