package com.grandvista.room.pricing;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Immutable, versioned pricing rules as consumed by {@link PricingEngine}. */
public record PricingPolicySnapshot(
        int version,
        Set<DayOfWeek> weekendDays,
        BigDecimal weekendMultiplier,
        BigDecimal minPriceFactor,
        BigDecimal maxPriceFactor,
        BigDecimal serviceFeeRate,
        BigDecimal taxRate,
        List<Season> seasons,
        List<DemandTier> demandTiers) {

    /** Inclusive date range [startDate, endDate]. */
    public record Season(String name, LocalDate startDate, LocalDate endDate, BigDecimal multiplier) {
        public boolean covers(LocalDate date) {
            return !date.isBefore(startDate) && !date.isAfter(endDate);
        }
    }

    /** Applies when the night's room-type occupancy is at least {@code minOccupancy} (0..1). */
    public record DemandTier(BigDecimal minOccupancy, BigDecimal multiplier) {
    }

    public PricingPolicySnapshot {
        weekendDays = Set.copyOf(weekendDays);
        seasons = List.copyOf(seasons);
        demandTiers = demandTiers.stream().sorted(Comparator.comparing(DemandTier::minOccupancy)).toList();
    }

    /** Seasons may not overlap (validated when a policy is published); the first match wins. */
    public Optional<Season> seasonFor(LocalDate date) {
        return seasons.stream().filter(s -> s.covers(date)).findFirst();
    }

    /** Highest tier whose threshold is reached; 1.0 when no tier applies. */
    public BigDecimal demandMultiplierFor(BigDecimal occupancy) {
        BigDecimal multiplier = BigDecimal.ONE;
        for (DemandTier tier : demandTiers) {
            if (occupancy.compareTo(tier.minOccupancy()) >= 0) {
                multiplier = tier.multiplier();
            }
        }
        return multiplier;
    }
}
