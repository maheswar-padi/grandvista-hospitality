package com.grandvista.room.reconciliation;

import com.grandvista.room.domain.AllocationStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Stock reconciliation rules: compares the room-service's allocations (the inventory) with the booking-service's
 * bookings (the demand) and classifies every mismatch. Pure and deterministic — no I/O — so it is unit-tested
 * directly; {@code InventoryReconciliationService} gathers the inputs and applies the actions.
 * <p>
 * Anything changed within {@code grace} is skipped: a booking that is in the middle of its saga (hold placed,
 * payment pending, confirmation in flight) is not a mismatch.
 */
public final class ReconciliationEngine {

    private ReconciliationEngine() {
    }

    public enum FindingType {
        /** HELD allocation whose booking is unknown or already finished (expired, cancelled, failed, rejected). */
        ORPHAN_HOLD,
        /** CONFIRMED allocation whose booking does not exist. */
        ALLOCATION_WITHOUT_BOOKING,
        /** Allocation and booking disagree (e.g. booking cancelled but room still confirmed). */
        STATUS_MISMATCH,
        /** CONFIRMED booking without an active allocation: the guest has no room. */
        CONFIRMED_BOOKING_WITHOUT_ALLOCATION,
        /** Booking awaiting payment without a hold, before its hold expiry. */
        PENDING_BOOKING_WITHOUT_HOLD
    }

    public enum Severity { WARNING, CRITICAL }

    public enum Action { NONE, RELEASE_EXPIRED_ORPHAN_HOLD }

    /** Booking statuses after which no room should be allocated any more. */
    static final Set<String> FINISHED = Set.of("EXPIRED", "CANCELLED", "PAYMENT_FAILED", "REJECTED");

    public record AllocationFact(UUID allocationId, UUID bookingId, UUID roomId, String roomNumber, LocalDate checkIn,
                                 LocalDate checkOut, AllocationStatus status, Instant holdExpiresAt, Instant updatedAt) {
        boolean active() {
            return status == AllocationStatus.HELD || status == AllocationStatus.CONFIRMED;
        }
    }

    public record BookingFact(UUID bookingId, String reference, String status, UUID roomId, LocalDate checkIn,
                              LocalDate checkOut, Instant holdExpiresAt, Instant updatedAt) {
    }

    public record Finding(FindingType type, Severity severity, Action action, UUID bookingId, String bookingReference,
                          UUID allocationId, UUID roomId, String roomNumber, LocalDate checkIn, LocalDate checkOut,
                          String allocationStatus, String bookingStatus, String detail) {
        /** Identity used to log a finding only when it first appears. */
        public String key() {
            return type + ":" + (allocationId != null ? allocationId : bookingId);
        }
    }

    /**
     * @param activeAllocations   HELD/CONFIRMED allocations of the property's rooms (current and future stays)
     * @param bookingsById        booking facts for the bookings of those allocations (unknown ids are absent)
     * @param holdingBookings     bookings in PENDING_PAYMENT or CONFIRMED on the property's rooms
     * @param allocationByBooking any allocation (any status) of those holding bookings
     */
    public static List<Finding> evaluate(List<AllocationFact> activeAllocations, Map<UUID, BookingFact> bookingsById,
            List<BookingFact> holdingBookings, Map<UUID, AllocationFact> allocationByBooking, Instant now, Duration grace) {
        List<Finding> findings = new ArrayList<>();
        Instant settled = now.minus(grace);

        for (AllocationFact a : activeAllocations) {
            if (a.updatedAt().isAfter(settled)) {
                continue;
            }
            BookingFact b = bookingsById.get(a.bookingId());
            boolean held = a.status() == AllocationStatus.HELD;
            boolean holdExpired = held && a.holdExpiresAt() != null && !a.holdExpiresAt().isAfter(now);
            if (b == null) {
                findings.add(held
                        ? finding(FindingType.ORPHAN_HOLD, Severity.WARNING, holdExpired ? Action.RELEASE_EXPIRED_ORPHAN_HOLD
                                : Action.NONE, a, null, "hold for a booking that does not exist")
                        : finding(FindingType.ALLOCATION_WITHOUT_BOOKING, Severity.CRITICAL, Action.NONE, a, null,
                                "room confirmed for a booking that does not exist; release it manually after checking"));
                continue;
            }
            if (b.updatedAt() != null && b.updatedAt().isAfter(settled)) {
                continue;
            }
            if (held && FINISHED.contains(b.status())) {
                findings.add(finding(FindingType.ORPHAN_HOLD, Severity.WARNING,
                        holdExpired ? Action.RELEASE_EXPIRED_ORPHAN_HOLD : Action.NONE, a, b,
                        "booking is " + b.status() + " but the room is still held"));
            } else if (held && "CONFIRMED".equals(b.status())) {
                findings.add(finding(FindingType.STATUS_MISMATCH, Severity.CRITICAL, Action.NONE, a, b,
                        "booking is CONFIRMED but the room is only HELD (the hold would expire)"));
            } else if (!held && FINISHED.contains(b.status())) {
                findings.add(finding(FindingType.STATUS_MISMATCH, Severity.CRITICAL, Action.NONE, a, b,
                        "booking is " + b.status() + " but the room is still CONFIRMED (blocks resale)"));
            }
        }

        for (BookingFact b : holdingBookings) {
            if (b.updatedAt() != null && b.updatedAt().isAfter(settled)) {
                continue;
            }
            AllocationFact a = allocationByBooking.get(b.bookingId());
            if (a != null && a.updatedAt().isAfter(settled)) {
                continue;
            }
            boolean hasActive = a != null && a.active();
            if ("CONFIRMED".equals(b.status()) && !hasActive) {
                findings.add(finding(FindingType.CONFIRMED_BOOKING_WITHOUT_ALLOCATION, Severity.CRITICAL, Action.NONE, a, b,
                        a == null ? "confirmed booking has no allocation" : "confirmed booking's allocation is " + a.status()));
            } else if ("PENDING_PAYMENT".equals(b.status()) && !hasActive
                    && (b.holdExpiresAt() == null || b.holdExpiresAt().isAfter(now))) {
                findings.add(finding(FindingType.PENDING_BOOKING_WITHOUT_HOLD, Severity.WARNING, Action.NONE, a, b,
                        a == null ? "booking awaiting payment has no hold" : "booking awaiting payment, hold is " + a.status()));
            }
        }
        findings.sort(Comparator.comparing(Finding::severity).reversed().thenComparing(Finding::type)
                .thenComparing(f -> String.valueOf(f.checkIn())));
        return findings;
    }

    private static Finding finding(FindingType type, Severity severity, Action action, AllocationFact a, BookingFact b,
            String detail) {
        return new Finding(type, severity, action, a != null ? a.bookingId() : b.bookingId(), b == null ? null : b.reference(),
                a == null ? null : a.allocationId(), a != null ? a.roomId() : b.roomId(), a == null ? null : a.roomNumber(),
                a != null ? a.checkIn() : b.checkIn(), a != null ? a.checkOut() : b.checkOut(),
                a == null ? null : a.status().name(), b == null ? null : b.status(), detail);
    }
}
