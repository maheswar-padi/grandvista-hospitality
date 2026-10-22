package com.grandvista.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PaymentStatusTest {

    @ParameterizedTest
    @CsvSource({
            "CREATED,PENDING,true", "CREATED,CANCELLED,true", "CREATED,SUCCEEDED,false",
            "PENDING,SUCCEEDED,true", "PENDING,FAILED,true", "PENDING,CANCELLED,false",
            "SUCCEEDED,REFUND_PENDING,true", "SUCCEEDED,FAILED,false", "SUCCEEDED,CREATED,false",
            "REFUND_PENDING,REFUNDED,true", "REFUND_PENDING,PARTIALLY_REFUNDED,true", "REFUND_PENDING,SUCCEEDED,true",
            "PARTIALLY_REFUNDED,REFUND_PENDING,true", "FAILED,SUCCEEDED,false", "CANCELLED,PENDING,false",
            "REFUNDED,REFUND_PENDING,false"})
    void transitionTable(PaymentStatus from, PaymentStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }

    @Test
    void paymentRejectsIllegalTransitionAndTracksRefundableAmount() {
        Instant now = Instant.now();
        Payment p = new Payment(UUID.randomUUID(), "GV-TEST1", UUID.randomUUID(), new BigDecimal("1000.00"), "INR",
                "SIMULATED", now.plus(Duration.ofMinutes(15)), now);
        assertThatThrownBy(() -> p.transitionTo(PaymentStatus.SUCCEEDED, now))
                .isInstanceOf(IllegalPaymentTransitionException.class);
        p.transitionTo(PaymentStatus.PENDING, now);
        p.transitionTo(PaymentStatus.SUCCEEDED, now);
        p.addRefunded(new BigDecimal("400.00"));
        assertThat(p.refundableAmount()).isEqualByComparingTo("600.00");
        assertThat(PaymentStatus.SUCCEEDED.isCaptured()).isTrue();
        assertThat(PaymentStatus.FAILED.isTerminal()).isTrue();
    }

    @Test
    void outboxBackoffGrowsAndDeadLetters() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        OutboxEvent e = new OutboxEvent(UUID.randomUUID(), UUID.randomUUID(), "PAYMENT_SUCCEEDED", "{}", null, now);
        e.markFailed("down", 3, Duration.ofMinutes(5), now);
        assertThat(e.getNextAttemptAt()).isEqualTo(now.plusSeconds(2));
        e.markFailed("down", 3, Duration.ofMinutes(5), now);
        assertThat(e.getNextAttemptAt()).isEqualTo(now.plusSeconds(4));
        e.markFailed("down", 3, Duration.ofMinutes(5), now);
        assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.DEAD);
    }
}
