package com.grandvista.booking.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Booking lifecycle (the saga's state). Only these transitions are legal:
 * <pre>
 * CREATED ──hold+payment created──▶ PENDING_PAYMENT ──payment ok + inventory confirmed──▶ CONFIRMED
 *   │  └─▶ REJECTED (room unavailable / price changed)        │                                 │
 *   └─▶ EXPIRED (orchestration never completed)              ├─▶ PAYMENT_FAILED                 │
 *                                                            ├─▶ EXPIRED (hold lapsed unpaid)   │
 *                                                            ├─▶ CANCELLED (abandoned)          │
 *                                                            └─▶ CANCELLATION_PENDING ◀─────────┘
 *                                       (paid but inventory lost → refund)   │
 *                                                            CANCELLED ◀──────┤
 *                                                            NEEDS_ATTENTION ◀┘ (refund rejected; ops)
 * </pre>
 */
public enum BookingStatus {
    CREATED,
    PENDING_PAYMENT,
    CONFIRMED,
    PAYMENT_FAILED,
    EXPIRED,
    REJECTED,
    CANCELLATION_PENDING,
    CANCELLED,
    NEEDS_ATTENTION;

    private static final Map<BookingStatus, Set<BookingStatus>> ALLOWED = Map.of(
            CREATED, EnumSet.of(PENDING_PAYMENT, REJECTED, EXPIRED),
            PENDING_PAYMENT, EnumSet.of(CONFIRMED, PAYMENT_FAILED, EXPIRED, CANCELLED, CANCELLATION_PENDING),
            CONFIRMED, EnumSet.of(CANCELLATION_PENDING),
            CANCELLATION_PENDING, EnumSet.of(CANCELLED, NEEDS_ATTENTION),
            NEEDS_ATTENTION, EnumSet.of(CANCELLATION_PENDING),
            PAYMENT_FAILED, EnumSet.noneOf(BookingStatus.class),
            EXPIRED, EnumSet.noneOf(BookingStatus.class),
            REJECTED, EnumSet.noneOf(BookingStatus.class),
            CANCELLED, EnumSet.noneOf(BookingStatus.class));

    public boolean canTransitionTo(BookingStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }
}
