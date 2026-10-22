package com.grandvista.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.grandvista.payment.config.PaymentProperties;
import com.grandvista.payment.domain.OutboxEvent;
import com.grandvista.payment.domain.Payment;
import com.grandvista.payment.repository.OutboxEventRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

/**
 * Consumer-facing contract of the PAYMENT_* events: path, method, headers and JSON fields that the
 * booking-service's {@code PaymentEventMessage} relies on, plus relay retry / dead-letter behaviour.
 */
class OutboxRelayContractTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneOffset.UTC);
    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private MockRestServiceServer server;
    private OutboxRelay relay;
    private OutboxEvent event;

    @BeforeEach
    void setUp() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://booking-service");
        server = MockRestServiceServer.bindTo(builder).build();
        relay = new OutboxRelay(repository, builder.build(), new PaymentProperties(null, null, null, null, null),
                mock(TransactionTemplate.class), clock);

        Payment payment = new Payment(UUID.randomUUID(), "GV-ABC123", UUID.randomUUID(), new BigDecimal("10584.00"),
                "INR", "SIMULATED", clock.instant().plus(Duration.ofMinutes(15)), clock.instant());
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var message = new OutboxService.PaymentEventMessage(UUID.randomUUID(), "PAYMENT_SUCCEEDED", 1, payment.getId(),
                payment.getBookingId(), "SUCCEEDED", payment.getAmount(), "INR", null, clock.instant());
        event = new OutboxEvent(message.eventId(), payment.getId(), "PAYMENT_SUCCEEDED", mapper.writeValueAsString(message),
                "corr-id-12345678", clock.instant());
        when(repository.claimNext(any())).thenReturn(List.of(event));
    }

    @Test
    void postsVersionedEventWithCorrelationIdAndMarksSent() {
        server.expect(requestTo("http://booking-service/internal/v1/payment-events"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Correlation-Id", "corr-id-12345678"))
                .andExpect(content().json("""
                        {"eventType":"PAYMENT_SUCCEEDED","schemaVersion":1,"status":"SUCCEEDED","amount":10584.00,
                         "currency":"INR"}
                        """))
                .andRespond(withSuccess());

        assertThat(relay.deliverNext()).isTrue();
        assertThat(event.getStatus()).isEqualTo(OutboxEvent.Status.SENT);
        server.verify();
    }

    @Test
    void serverErrorSchedulesRetryAndStopsTheRun() {
        server.expect(requestTo("http://booking-service/internal/v1/payment-events")).andRespond(withServerError());
        assertThat(relay.deliverNext()).isFalse();
        assertThat(event.getStatus()).isEqualTo(OutboxEvent.Status.PENDING);
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getNextAttemptAt()).isAfter(clock.instant());
    }

    @Test
    void contractRejectionIsDeadLettered() {
        server.expect(requestTo("http://booking-service/internal/v1/payment-events")).andRespond(withBadRequest());
        relay.deliverNext();
        assertThat(event.getStatus()).isEqualTo(OutboxEvent.Status.DEAD);
    }
}
