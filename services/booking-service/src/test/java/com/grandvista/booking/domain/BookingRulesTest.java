package com.grandvista.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BookingRulesTest {

    private final CancellationPolicy policy = new CancellationPolicy(7, 2, 50);
    private final LocalDate today = LocalDate.of(2026, 11, 1);
    private final BigDecimal total = new BigDecimal("10584.00");

    @ParameterizedTest(name = "{0} days before check-in -> {1}% refund (allowed={2})")
    @CsvSource({"30,100,true", "7,100,true", "6,50,true", "2,50,true", "1,0,true", "0,0,false", "-1,0,false"})
    void cancellationPolicyBoundaries(int daysBefore, int percent, boolean allowed) {
        var outcome = policy.evaluate(total, today.plusDays(daysBefore), today, false);
        assertThat(outcome.allowed()).isEqualTo(allowed);
        assertThat(outcome.refundPercent()).isEqualTo(percent);
        if (allowed) {
            assertThat(outcome.refundAmount()).isEqualByComparingTo(
                    total.multiply(BigDecimal.valueOf(percent)).divide(BigDecimal.valueOf(100)));
        }
    }

    @Test
    void halfRefundRoundsToCents() {
        var outcome = policy.evaluate(new BigDecimal("1000.01"), today.plusDays(3), today, false);
        assertThat(outcome.refundAmount()).isEqualByComparingTo("500.01"); // 500.005 -> HALF_UP
    }

    @Test
    void hotelInitiatedCancellationAlwaysRefundsInFull() {
        var outcome = policy.evaluate(total, today.plusDays(1), today, true);
        assertThat(outcome.refundPercent()).isEqualTo(100);
        assertThat(outcome.refundAmount()).isEqualByComparingTo(total);
    }

    @ParameterizedTest
    @CsvSource({
            "CREATED,PENDING_PAYMENT,true", "CREATED,CONFIRMED,false", "CREATED,REJECTED,true",
            "PENDING_PAYMENT,CONFIRMED,true", "PENDING_PAYMENT,PAYMENT_FAILED,true", "PENDING_PAYMENT,EXPIRED,true",
            "CONFIRMED,CANCELLED,false", "CONFIRMED,CANCELLATION_PENDING,true", "CONFIRMED,PENDING_PAYMENT,false",
            "CANCELLATION_PENDING,CANCELLED,true", "CANCELLED,CONFIRMED,false", "EXPIRED,CONFIRMED,false",
            "PAYMENT_FAILED,CONFIRMED,false", "NEEDS_ATTENTION,CANCELLATION_PENDING,true"})
    void transitionTable(BookingStatus from, BookingStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }

    @Test
    void bookingRecordsEveryTransitionAndRejectsIllegalOnes() {
        Booking b = new Booking("GV-TEST01", UUID.randomUUID(), "a@b.test", UUID.randomUUID(), today.plusDays(10),
                today.plusDays(12), 2, "key-12345678", "fp", null, Instant.now());
        assertThat(b.getNights()).isEqualTo(2);
        assertThatThrownBy(() -> b.transitionTo(BookingStatus.CONFIRMED, "skip", "test", Instant.now()))
                .isInstanceOf(IllegalBookingTransitionException.class);
        b.transitionTo(BookingStatus.PENDING_PAYMENT, "held", "test", Instant.now());
        b.transitionTo(BookingStatus.CONFIRMED, "paid", "test", Instant.now());
        assertThat(b.getConfirmedAt()).isNotNull();
        assertThat(b.getHistory()).extracting(BookingStatusHistory::getToStatus)
                .containsExactly(BookingStatus.CREATED, BookingStatus.PENDING_PAYMENT, BookingStatus.CONFIRMED);
    }
}
