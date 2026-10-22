package com.grandvista.room.pricing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * An itemised, authoritative price for one stay. The booking-service stores this snapshot with the
 * reservation so later policy or base-price changes never alter an existing booking.
 */
public record PriceQuote(
        String currency,
        LocalDate checkIn,
        LocalDate checkOut,
        int nights,
        List<NightlyRate> nightlyRates,
        BigDecimal subtotal,
        BigDecimal serviceFee,
        BigDecimal taxes,
        BigDecimal total,
        BigDecimal averageNightlyRate,
        BigDecimal serviceFeeRate,
        BigDecimal taxRate,
        int pricingPolicyVersion,
        Instant quotedAt,
        Instant validUntil) {

    public record NightlyRate(
            LocalDate date,
            BigDecimal baseRate,
            BigDecimal dayMultiplier,
            String seasonName,
            BigDecimal seasonMultiplier,
            BigDecimal occupancy,
            BigDecimal demandMultiplier,
            /** "MIN" or "MAX" when the policy's price limits were applied, otherwise null. */
            String limitApplied,
            BigDecimal rate) {
    }
}
