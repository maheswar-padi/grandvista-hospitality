package com.grandvista.room.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grandvista.platform.error.ApiException;
import com.grandvista.room.config.HotelProperties;
import com.grandvista.room.domain.AllocationStatus;
import com.grandvista.room.domain.InventoryAllocation;
import com.grandvista.room.domain.InventoryEvent;
import com.grandvista.room.domain.Room;
import com.grandvista.room.pricing.PriceQuote;
import com.grandvista.room.pricing.StayDates;
import com.grandvista.room.repository.InventoryAllocationRepository;
import com.grandvista.room.repository.RoomRepository;
import com.grandvista.room.web.dto.InventoryDtos.HoldRequest;
import com.grandvista.room.web.dto.InventoryDtos.HoldResponse;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inventory holds and allocations used by the booking saga.
 *
 * <p>Consistency rests on the database: the {@code ex_allocations_no_overlap} exclusion constraint makes
 * it impossible for two HELD/CONFIRMED allocations of the same room to overlap, whatever the interleaving
 * of concurrent requests. Application-level checks only produce friendlier errors; they are not the guard.
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);
    private static final String EXCLUSION_VIOLATION = "23P01";
    private static final String UNIQUE_VIOLATION = "23505";

    private final InventoryAllocationRepository allocations;
    private final RoomRepository rooms;
    private final PricingService pricing;
    private final StayRules stayRules;
    private final InventoryAuditService audit;
    private final HotelProperties hotel;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public InventoryService(InventoryAllocationRepository allocations, RoomRepository rooms, PricingService pricing,
            StayRules stayRules, InventoryAuditService audit, HotelProperties hotel, ObjectMapper objectMapper, Clock clock) {
        this.allocations = allocations;
        this.rooms = rooms;
        this.pricing = pricing;
        this.stayRules = stayRules;
        this.audit = audit;
        this.hotel = hotel;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Places a temporary hold and returns the authoritative price. Idempotent per bookingId: a repeated
     * request returns the original hold (and its original price) instead of creating a second one.
     */
    @Transactional
    public HoldResponse hold(HoldRequest request) {
        var existing = allocations.findByBookingId(request.bookingId());
        if (existing.isPresent()) {
            InventoryAllocation a = existing.get();
            if (!a.matches(request.roomId(), request.checkIn(), request.checkOut())) {
                throw ApiException.conflict("HOLD_MISMATCH",
                        "A different hold already exists for this booking.");
            }
            return toResponse(a, rooms.findWithDetailsById(a.getRoomId()).orElseThrow());
        }

        Room room = rooms.findByIdForUpdate(request.roomId())
                .orElseThrow(() -> ApiException.notFound("ROOM_NOT_FOUND", "Room not found."));
        if (!room.getProperty().isActive()) {
            throw ApiException.conflict("ROOM_NOT_AVAILABLE", "This property is not taking bookings.",
                    Map.of("reason", "PROPERTY_INACTIVE"));
        }
        // Stay dates are calendar dates at the room's property.
        StayDates stay = stayRules.validate(request.checkIn(), request.checkOut(), room.getProperty().zoneId());
        if (!room.getStatus().isSellable()) {
            throw ApiException.conflict("ROOM_NOT_AVAILABLE", "This room is not available for booking.",
                    Map.of("reason", "ROOM_" + room.getStatus().name()));
        }
        if (request.guests() > room.getMaxOccupancy()) {
            throw ApiException.unprocessable("GUEST_COUNT_EXCEEDS_CAPACITY",
                    "This room accommodates at most " + room.getMaxOccupancy() + " guests.");
        }

        Instant now = clock.instant();
        // Lazily expire stale holds that would otherwise still block these dates in the constraint.
        List<InventoryAllocation> stale = allocations.findStaleHoldsForUpdate(room.getId(), stay.checkIn(),
                stay.checkOut(), now);
        for (InventoryAllocation s : stale) {
            s.expire(now);
            audit.record(InventoryEvent.Type.HOLD_EXPIRED, s.getRoomId(), s.getId(), s.getBookingId(), "expired lazily");
        }
        if (!stale.isEmpty()) {
            allocations.flush();
        }

        PriceQuote quote = pricing.quote(room, stay);
        InventoryAllocation allocation = InventoryAllocation.hold(request.bookingId(), room.getId(), stay.checkIn(),
                stay.checkOut(), request.guests().shortValue(), now.plus(hotel.holdTtl()), toJson(quote), now);
        try {
            allocations.saveAndFlush(allocation);
        } catch (DataIntegrityViolationException ex) {
            throw translateConflict(ex);
        }
        audit.record(InventoryEvent.Type.HOLD_CREATED, room.getId(), allocation.getId(), request.bookingId(),
                stay.checkIn() + ".." + stay.checkOut() + " total " + quote.total() + " " + quote.currency());
        log.info("Hold created for booking {} on room {} {}..{}", request.bookingId(), room.getRoomNumber(),
                stay.checkIn(), stay.checkOut());
        return toResponse(allocation, room, quote);
    }

    @Transactional(readOnly = true)
    public HoldResponse get(UUID bookingId) {
        InventoryAllocation a = allocations.findByBookingId(bookingId)
                .orElseThrow(() -> ApiException.notFound("HOLD_NOT_FOUND", "No hold exists for this booking."));
        return toResponse(a, rooms.findWithDetailsById(a.getRoomId()).orElseThrow());
    }

    /**
     * Converts the hold into a confirmed allocation after payment. Idempotent. If the hold had already
     * expired, the room is re-acquired when it is still free; otherwise {@code INVENTORY_NO_LONGER_AVAILABLE}
     * is returned and the booking-service compensates (refund).
     */
    @Transactional
    public HoldResponse confirm(UUID bookingId) {
        UUID roomId = allocations.findByBookingId(bookingId).map(InventoryAllocation::getRoomId)
                .orElseThrow(() -> ApiException.notFound("HOLD_NOT_FOUND", "No hold exists for this booking."));
        // Same lock order as hold(): room first, then allocation — avoids lock-order deadlocks on re-acquire.
        rooms.findByIdForUpdate(roomId);
        InventoryAllocation a = allocations.findByBookingIdForUpdate(bookingId).orElseThrow();
        Instant now = clock.instant();
        switch (a.getStatus()) {
            case CONFIRMED -> {
                return toResponse(a, rooms.findWithDetailsById(a.getRoomId()).orElseThrow());
            }
            case RELEASED -> throw ApiException.conflict("HOLD_RELEASED", "The hold was released and cannot be confirmed.");
            case HELD, EXPIRED -> {
                boolean reacquire = a.getStatus() == AllocationStatus.EXPIRED;
                a.confirm(now);
                try {
                    allocations.saveAndFlush(a);
                } catch (DataIntegrityViolationException ex) {
                    if (isSqlState(ex, EXCLUSION_VIOLATION)) {
                        throw ApiException.conflict("INVENTORY_NO_LONGER_AVAILABLE",
                                "The hold expired and the room has since been reserved by someone else.");
                    }
                    throw ex;
                }
                audit.record(reacquire ? InventoryEvent.Type.HOLD_REACQUIRED : InventoryEvent.Type.HOLD_CONFIRMED,
                        a.getRoomId(), a.getId(), bookingId, null);
                log.info("Allocation confirmed for booking {}{}", bookingId, reacquire ? " (re-acquired after expiry)" : "");
            }
            default -> throw new IllegalStateException("Unexpected status " + a.getStatus());
        }
        return toResponse(a, rooms.findWithDetailsById(a.getRoomId()).orElseThrow());
    }

    /** Frees the room. Idempotent and safe to call for bookings that never obtained a hold. */
    @Transactional
    public void release(UUID bookingId, String reason) {
        allocations.findByBookingIdForUpdate(bookingId).ifPresent(a -> {
            if (a.getStatus().blocksInventory()) {
                a.release(reason, clock.instant());
                audit.record(InventoryEvent.Type.HOLD_RELEASED, a.getRoomId(), a.getId(), bookingId, reason);
                log.info("Allocation released for booking {} ({})", bookingId, reason);
            }
        });
    }

    /** Called by {@link HoldExpiryJob}; uses SKIP LOCKED so concurrent sweepers never process the same row. */
    @Transactional
    public int expireDueHolds(int batchSize) {
        Instant now = clock.instant();
        List<InventoryAllocation> due = allocations.findDueHoldsSkipLocked(now, batchSize);
        for (InventoryAllocation a : due) {
            a.expire(now);
            audit.record(InventoryEvent.Type.HOLD_EXPIRED, a.getRoomId(), a.getId(), a.getBookingId(), "hold TTL elapsed");
        }
        return due.size();
    }

    private ApiException translateConflict(DataIntegrityViolationException ex) {
        if (isSqlState(ex, EXCLUSION_VIOLATION)) {
            return ApiException.conflict("ROOM_UNAVAILABLE", "The room is no longer available for the selected dates.");
        }
        if (isSqlState(ex, UNIQUE_VIOLATION)) {
            return new ApiException(HttpStatus.CONFLICT, "HOLD_IN_PROGRESS",
                    "A hold for this booking is being created concurrently. Retry the request.");
        }
        return ApiException.conflict("DATA_CONFLICT", "The hold could not be created.");
    }

    private static boolean isSqlState(Throwable ex, String state) {
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(ex);
        return cause instanceof SQLException sql && state.equals(sql.getSQLState());
    }

    private HoldResponse toResponse(InventoryAllocation a, Room room) {
        return toResponse(a, room, fromJson(a.getPriceQuote()));
    }

    private HoldResponse toResponse(InventoryAllocation a, Room room, PriceQuote quote) {
        return new HoldResponse(a.getId(), a.getBookingId(), room.getProperty().getId(), room.getProperty().getName(),
                room.getProperty().getCity(), room.getProperty().getTimeZone(), room.getId(), room.getRoomNumber(), room.getName(),
                room.getRoomType().getName(), a.getCheckIn(), a.getCheckOut(), a.getGuests(), a.getStatus(),
                a.getHoldExpiresAt(), a.getConfirmedAt(), quote);
    }

    private String toJson(PriceQuote quote) {
        try {
            return objectMapper.writeValueAsString(quote);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private PriceQuote fromJson(String json) {
        try {
            return objectMapper.readValue(json, PriceQuote.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored price quote is unreadable", ex);
        }
    }
}
