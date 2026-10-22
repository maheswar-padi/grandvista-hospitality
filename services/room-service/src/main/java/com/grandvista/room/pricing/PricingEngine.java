package com.grandvista.room.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Deterministic dynamic-pricing calculation. Given the same inputs it always returns the same quote.
 *
 * <pre>
 * rawNightly   = basePrice × dayMultiplier × seasonMultiplier × demandMultiplier
 * nightlyRate  = round2( clamp(rawNightly, basePrice × minPriceFactor, basePrice × maxPriceFactor) )
 * subtotal     = Σ nightlyRate
 * serviceFee   = round2( subtotal × serviceFeeRate )
 * taxes        = round2( (subtotal + serviceFee) × taxRate )
 * total        = subtotal + serviceFee + taxes
 * </pre>
 *
 * All arithmetic uses {@link BigDecimal}; rounding is HALF_UP to 2 decimal places and happens only at the
 * points shown above, so intermediate products are exact.
 */
public final class PricingEngine {

    public static final int MONEY_SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private PricingEngine() {
    }

    /**
     * @param occupancyByNight occupancy ratio (0..1) of the room type for a given night; drives demand pricing
     */
    public static PriceQuote quote(BigDecimal basePrice, String currency, StayDates stay, PricingPolicySnapshot policy,
            Function<LocalDate, BigDecimal> occupancyByNight, Instant quotedAt, Duration validity) {
        Objects.requireNonNull(basePrice, "basePrice");
        if (basePrice.signum() <= 0) {
            throw new IllegalArgumentException("basePrice must be positive");
        }
        BigDecimal floor = basePrice.multiply(policy.minPriceFactor());
        BigDecimal ceiling = basePrice.multiply(policy.maxPriceFactor());

        List<PriceQuote.NightlyRate> nightly = new ArrayList<>(stay.nights());
        BigDecimal subtotal = BigDecimal.ZERO;
        for (LocalDate night : stay.nightDates()) {
            BigDecimal dayMultiplier = policy.weekendDays().contains(night.getDayOfWeek())
                    ? policy.weekendMultiplier() : BigDecimal.ONE;
            var season = policy.seasonFor(night);
            BigDecimal seasonMultiplier = season.map(PricingPolicySnapshot.Season::multiplier).orElse(BigDecimal.ONE);
            BigDecimal occupancy = clampRatio(occupancyByNight.apply(night));
            BigDecimal demandMultiplier = policy.demandMultiplierFor(occupancy);

            BigDecimal raw = basePrice.multiply(dayMultiplier).multiply(seasonMultiplier).multiply(demandMultiplier);
            String limit = null;
            if (raw.compareTo(floor) < 0) {
                raw = floor;
                limit = "MIN";
            } else if (raw.compareTo(ceiling) > 0) {
                raw = ceiling;
                limit = "MAX";
            }
            BigDecimal rate = money(raw);
            subtotal = subtotal.add(rate);
            nightly.add(new PriceQuote.NightlyRate(night, money(basePrice), dayMultiplier,
                    season.map(PricingPolicySnapshot.Season::name).orElse(null), seasonMultiplier,
                    occupancy.setScale(4, ROUNDING), demandMultiplier, limit, rate));
        }

        BigDecimal serviceFee = money(subtotal.multiply(policy.serviceFeeRate()));
        BigDecimal taxes = money(subtotal.add(serviceFee).multiply(policy.taxRate()));
        BigDecimal total = subtotal.add(serviceFee).add(taxes);
        BigDecimal average = subtotal.divide(BigDecimal.valueOf(stay.nights()), MONEY_SCALE, ROUNDING);

        return new PriceQuote(currency, stay.checkIn(), stay.checkOut(), stay.nights(), List.copyOf(nightly),
                subtotal, serviceFee, taxes, total, average, policy.serviceFeeRate(), policy.taxRate(),
                policy.version(), quotedAt, quotedAt.plus(validity));
    }

    static BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, ROUNDING);
    }

    private static BigDecimal clampRatio(BigDecimal ratio) {
        if (ratio == null || ratio.signum() < 0) {
            return BigDecimal.ZERO;
        }
        return ratio.compareTo(BigDecimal.ONE) > 0 ? BigDecimal.ONE : ratio;
    }
}
