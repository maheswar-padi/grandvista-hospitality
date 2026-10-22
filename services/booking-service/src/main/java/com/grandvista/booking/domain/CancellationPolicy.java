package com.grandvista.booking.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Documented cancellation policy, measured in hotel-local calendar days before check-in:
 * <ul>
 *   <li>{@code fullRefundDays} or more days before check-in: 100% refund</li>
 *   <li>{@code partialRefundDays} or more (but fewer than full): {@code partialRefundPercent}% refund</li>
 *   <li>fewer days, but before the check-in date: cancellation allowed, no refund</li>
 *   <li>on or after the check-in date: cancellation is no longer possible online</li>
 * </ul>
 * Hotel-initiated cancellations (by staff/administrators) are always refunded in full.
 */
public record CancellationPolicy(int fullRefundDays, int partialRefundDays, int partialRefundPercent) {

    public CancellationPolicy {
        if (fullRefundDays < partialRefundDays || partialRefundDays < 1 || partialRefundPercent < 0 || partialRefundPercent > 100) {
            throw new IllegalArgumentException("Inconsistent cancellation policy");
        }
    }

    public record Outcome(boolean allowed, int refundPercent, BigDecimal refundAmount, long daysBeforeCheckIn, String rule) {
    }

    public Outcome evaluate(BigDecimal total, LocalDate checkIn, LocalDate today, boolean hotelInitiated) {
        long days = ChronoUnit.DAYS.between(today, checkIn);
        if (hotelInitiated) {
            return new Outcome(true, 100, total, days, "HOTEL_INITIATED_FULL_REFUND");
        }
        if (days <= 0) {
            return new Outcome(false, 0, BigDecimal.ZERO.setScale(2), days, "CHECK_IN_REACHED");
        }
        int percent = days >= fullRefundDays ? 100 : days >= partialRefundDays ? partialRefundPercent : 0;
        String rule = percent == 100 ? "FULL_REFUND" : percent > 0 ? "PARTIAL_REFUND" : "NO_REFUND";
        BigDecimal refund = total.multiply(BigDecimal.valueOf(percent)).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        return new Outcome(true, percent, refund, days, rule);
    }
}
