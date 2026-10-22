package com.grandvista.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.grandvista.notification.messaging.Events;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Consumer side of /contracts: the published event examples must map onto this service's event views. */
class EventContractTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void bookingEventContractIsReadable() throws Exception {
        var event = mapper.readValue(Files.readString(Path.of("../../contracts/booking-service/booking-event.json")),
                Events.BookingEvent.class);
        assertThat(event.eventId()).isNotNull();
        assertThat(event.bookingId()).isNotNull();
        assertThat(event.reference()).isEqualTo("GV-K7M2QX");
        assertThat(event.customerEmail()).isEqualTo("asha@example.test");
        assertThat(event.checkIn()).isEqualTo(LocalDate.of(2026, 12, 18));
        assertThat(event.totalAmount()).isEqualByComparingTo("10584.00");
        assertThat(event.occurredAt()).isNotNull();
    }

    @Test
    void paymentEventContractIsReadable() throws Exception {
        var event = mapper.readValue(Files.readString(Path.of("../../contracts/payment-service/payment-event.json")),
                Events.PaymentEvent.class);
        assertThat(event.eventType()).isEqualTo("PAYMENT_SUCCEEDED");
        assertThat(event.bookingId()).isNotNull();
        assertThat(event.amount()).isNotNull();
        assertThat(event.provider()).isEqualTo("SIMULATED");
    }
}
