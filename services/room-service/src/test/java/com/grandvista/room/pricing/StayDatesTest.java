package com.grandvista.room.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class StayDatesTest {

    private final LocalDate d1 = LocalDate.of(2026, 12, 1);

    private StayDates stay(int from, int to) {
        return new StayDates(d1.plusDays(from), d1.plusDays(to));
    }

    @Test
    void halfOpenIntervalSemantics() {
        assertThat(stay(0, 3).nights()).isEqualTo(3);
        assertThat(stay(0, 3).nightDates()).containsExactly(d1, d1.plusDays(1), d1.plusDays(2));
    }

    @Test
    void overlapRules() {
        assertThat(stay(0, 3).overlaps(stay(2, 5))).isTrue();   // partial overlap
        assertThat(stay(0, 5).overlaps(stay(1, 2))).isTrue();   // containment
        assertThat(stay(0, 3).overlaps(stay(0, 3))).isTrue();   // identical
        assertThat(stay(0, 3).overlaps(stay(3, 5))).isFalse();  // back-to-back: check-out day is free
        assertThat(stay(3, 5).overlaps(stay(0, 3))).isFalse();
        assertThat(stay(0, 2).overlaps(stay(4, 6))).isFalse();  // disjoint
    }
}
