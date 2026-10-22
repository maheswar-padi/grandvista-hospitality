package com.grandvista.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EventTopologyTest {

    @Test
    void routingKeysFollowTheDocumentedConvention() {
        assertThat(EventTopology.routingKey(EventTopology.PAYMENT_SUCCEEDED)).isEqualTo("payment.succeeded");
        assertThat(EventTopology.routingKey(EventTopology.PAYMENT_FAILED)).isEqualTo("payment.failed");
        assertThat(EventTopology.routingKey(EventTopology.BOOKING_CREATED)).isEqualTo("booking.created");
        assertThat(EventTopology.routingKey(EventTopology.BOOKING_CONFIRMED)).isEqualTo("booking.confirmed");
        assertThat(EventTopology.routingKey(EventTopology.BOOKING_CANCELLED)).isEqualTo("booking.cancelled");
        assertThat(EventTopology.routingKey(EventTopology.HOLD_EXPIRED)).isEqualTo("booking.hold-expired");
        assertThat(EventTopology.deadLetterQueue("notification.events")).isEqualTo("notification.events.dlq");
    }

    @Test
    void unknownEventTypesAreRejected() {
        assertThatThrownBy(() -> EventTopology.routingKey("SOMETHING")).isInstanceOf(IllegalArgumentException.class);
    }
}
