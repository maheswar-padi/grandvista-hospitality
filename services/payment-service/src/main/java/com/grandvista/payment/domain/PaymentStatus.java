package com.grandvista.payment.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Payment lifecycle. Only the transitions listed here are legal; every change goes through
 * {@link Payment#transitionTo} which enforces them.
 * <pre>
 * CREATED ──▶ PENDING ──▶ SUCCEEDED ──▶ REFUND_PENDING ──▶ REFUNDED | PARTIALLY_REFUNDED
 *    │           └──────▶ FAILED                  └──────▶ SUCCEEDED (refund failed)
 *    └──▶ CANCELLED (booking expired/cancelled before payment)
 * </pre>
 */
public enum PaymentStatus {
    CREATED,
    PENDING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    REFUND_PENDING,
    PARTIALLY_REFUNDED,
    REFUNDED;

    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED = Map.of(
            CREATED, EnumSet.of(PENDING, CANCELLED),
            PENDING, EnumSet.of(SUCCEEDED, FAILED),
            SUCCEEDED, EnumSet.of(REFUND_PENDING),
            REFUND_PENDING, EnumSet.of(REFUNDED, PARTIALLY_REFUNDED, SUCCEEDED),
            PARTIALLY_REFUNDED, EnumSet.of(REFUND_PENDING),
            FAILED, EnumSet.noneOf(PaymentStatus.class),
            CANCELLED, EnumSet.noneOf(PaymentStatus.class),
            REFUNDED, EnumSet.noneOf(PaymentStatus.class));

    public boolean canTransitionTo(PaymentStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }

    /** Money was captured (possibly partly refunded since). */
    public boolean isCaptured() {
        return this == SUCCEEDED || this == REFUND_PENDING || this == PARTIALLY_REFUNDED || this == REFUNDED;
    }
}
