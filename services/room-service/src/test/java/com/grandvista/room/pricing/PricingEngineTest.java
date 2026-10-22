package com.grandvista.room.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PricingEngineTest {

    private static final Instant NOW = Instant.parse("2026-11-01T06:00:00Z");
    // 2026-11-02 is a Monday
    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);

    private static PricingPolicySnapshot policy(List<PricingPolicySnapshot.Season> seasons) {
        return new PricingPolicySnapshot(3, EnumSet.of(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY), new BigDecimal("1.15"),
                new BigDecimal("0.80"), new BigDecimal("1.80"), new BigDecimal("0.05"), new BigDecimal("0.12"),
                seasons,
                List.of(new PricingPolicySnapshot.DemandTier(BigDecimal.ZERO, BigDecimal.ONE),
                        new PricingPolicySnapshot.DemandTier(new BigDecimal("0.5"), new BigDecimal("1.10")),
                        new PricingPolicySnapshot.DemandTier(new BigDecimal("0.8"), new BigDecimal("1.25"))));
    }

    private static PriceQuote quote(String base, LocalDate in, LocalDate out, PricingPolicySnapshot p,
            Map<LocalDate, BigDecimal> occupancy) {
        return PricingEngine.quote(new BigDecimal(base), "INR", new StayDates(in, out), p,
                d -> occupancy.getOrDefault(d, BigDecimal.ZERO), NOW, Duration.ofMinutes(15));
    }

    @Test
    void weekdayStayWithoutModifiersUsesBaseRateAndDocumentedFeeAndTaxFormula() {
        PriceQuote q = quote("4500.00", MONDAY, MONDAY.plusDays(2), policy(List.of()), Map.of());

        assertThat(q.nights()).isEqualTo(2);
        assertThat(q.nightlyRates()).extracting(PriceQuote.NightlyRate::rate)
                .containsExactly(new BigDecimal("4500.00"), new BigDecimal("4500.00"));
        assertThat(q.subtotal()).isEqualByComparingTo("9000.00");
        assertThat(q.serviceFee()).isEqualByComparingTo("450.00");      // 5% of subtotal
        assertThat(q.taxes()).isEqualByComparingTo("1134.00");          // 12% of (9000 + 450)
        assertThat(q.total()).isEqualByComparingTo("10584.00");
        assertThat(q.averageNightlyRate()).isEqualByComparingTo("4500.00");
        assertThat(q.pricingPolicyVersion()).isEqualTo(3);
        assertThat(q.validUntil()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(q.currency()).isEqualTo("INR");
    }

    @Test
    void checkOutNightIsNotChargedAndWeekendNightsAreMarkedUp() {
        LocalDate friday = MONDAY.plusDays(4);
        PriceQuote q = quote("4000.00", friday, friday.plusDays(3), policy(List.of()), Map.of()); // Fri, Sat, Sun nights

        assertThat(q.nightlyRates()).extracting(PriceQuote.NightlyRate::date)
                .containsExactly(friday, friday.plusDays(1), friday.plusDays(2));
        assertThat(q.nightlyRates()).extracting(PriceQuote.NightlyRate::rate)
                .containsExactly(new BigDecimal("4600.00"), new BigDecimal("4600.00"), new BigDecimal("4000.00"));
        assertThat(q.subtotal()).isEqualByComparingTo("13200.00");
    }

    @Test
    void seasonalAndDemandMultipliersCompoundPerNight() {
        var season = new PricingPolicySnapshot.Season("Festive", MONDAY, MONDAY, new BigDecimal("1.30"));
        PriceQuote q = quote("5000.00", MONDAY, MONDAY.plusDays(2), policy(List.of(season)),
                Map.of(MONDAY, new BigDecimal("0.85"), MONDAY.plusDays(1), new BigDecimal("0.55")));

        PriceQuote.NightlyRate first = q.nightlyRates().get(0);
        assertThat(first.seasonName()).isEqualTo("Festive");
        assertThat(first.demandMultiplier()).isEqualByComparingTo("1.25");
        // 5000 × 1.30 × 1.25 = 8125 → within max (5000 × 1.8 = 9000)
        assertThat(first.rate()).isEqualByComparingTo("8125.00");
        assertThat(first.limitApplied()).isNull();

        PriceQuote.NightlyRate second = q.nightlyRates().get(1);
        assertThat(second.seasonName()).isNull();
        assertThat(second.demandMultiplier()).isEqualByComparingTo("1.10");
        assertThat(second.rate()).isEqualByComparingTo("5500.00");
    }

    @Test
    void ratesAreClampedToPolicyLimits() {
        LocalDate friday = MONDAY.plusDays(4);
        var peak = new PricingPolicySnapshot.Season("Peak", friday, friday, new BigDecimal("2.0"));
        var trough = new PricingPolicySnapshot.Season("Trough", MONDAY, MONDAY, new BigDecimal("0.5"));

        PriceQuote high = quote("1000.00", friday, friday.plusDays(1), policy(List.of(peak)), Map.of(friday, BigDecimal.ONE));
        // 1000 × 1.15 × 2.0 × 1.25 = 2875 → capped at 1800
        assertThat(high.nightlyRates().get(0).rate()).isEqualByComparingTo("1800.00");
        assertThat(high.nightlyRates().get(0).limitApplied()).isEqualTo("MAX");

        PriceQuote low = quote("1000.00", MONDAY, MONDAY.plusDays(1), policy(List.of(trough)), Map.of());
        assertThat(low.nightlyRates().get(0).rate()).isEqualByComparingTo("800.00");
        assertThat(low.nightlyRates().get(0).limitApplied()).isEqualTo("MIN");
    }

    @Test
    void roundingIsHalfUpToTwoDecimalsAndTotalsAreExactSums() {
        // 3333.33 × 1.15 = 3833.3295 → 3833.33 on the Friday
        LocalDate thursday = MONDAY.plusDays(3);
        PriceQuote q = quote("3333.33", thursday, thursday.plusDays(2), policy(List.of()), Map.of());
        assertThat(q.nightlyRates().get(1).rate()).isEqualByComparingTo("3833.33");
        assertThat(q.subtotal()).isEqualByComparingTo("7166.66");
        assertThat(q.serviceFee()).isEqualByComparingTo("358.33");  // 358.333
        assertThat(q.taxes()).isEqualByComparingTo("903.00");      // (7166.66+358.33)×0.12 = 902.9988
        assertThat(q.total()).isEqualByComparingTo(q.subtotal().add(q.serviceFee()).add(q.taxes()));
        assertThat(q.total().scale()).isEqualTo(2);
    }

    @Test
    void calculationIsDeterministic() {
        var p = policy(List.of());
        Map<LocalDate, BigDecimal> occ = Map.of(MONDAY, new BigDecimal("0.6"));
        assertThat(quote("4999.99", MONDAY, MONDAY.plusDays(5), p, occ))
                .isEqualTo(quote("4999.99", MONDAY, MONDAY.plusDays(5), p, occ));
    }

    @Test
    void occupancyOutsideZeroToOneIsClamped() {
        PriceQuote q = quote("1000.00", MONDAY, MONDAY.plusDays(1), policy(List.of()), Map.of(MONDAY, new BigDecimal("4.2")));
        assertThat(q.nightlyRates().get(0).occupancy()).isEqualByComparingTo("1");
        assertThat(q.nightlyRates().get(0).demandMultiplier()).isEqualByComparingTo("1.25");
    }

    @Test
    void rejectsInvalidInputs() {
        assertThatThrownBy(() -> new StayDates(MONDAY, MONDAY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StayDates(MONDAY, MONDAY.minusDays(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> quote("0", MONDAY, MONDAY.plusDays(1), policy(List.of()), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
