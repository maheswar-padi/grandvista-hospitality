package com.grandvista.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grandvista.booking.client.PaymentServiceClient;
import com.grandvista.booking.client.RoomServiceClient;
import com.grandvista.platform.messaging.EventTopology;
import com.grandvista.platform.messaging.RabbitDeclarations;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Booking events and payment events over a real RabbitMQ broker: the outbox relay publishes BOOKING_* events,
 * PAYMENT_* events consumed from the booking.payment-events queue drive the saga exactly once, and malformed
 * messages are dead-lettered.
 */
@SpringBootTest(properties = {"eureka.client.enabled=false", "gv.security.jwt.jwk-set-uri=http://localhost:1/unused",
        "gv.booking.recovery.interval=1h", "gv.booking.events.publish-enabled=true",
        "gv.booking.events.consume-enabled=true", "gv.booking.events.initial-delay=0s",
        "gv.booking.events.relay-interval=200ms", "spring.rabbitmq.listener.simple.retry.initial-interval=100ms",
        "spring.rabbitmq.listener.simple.retry.max-interval=200ms", "spring.rabbitmq.listener.simple.retry.max-attempts=3"})
@AutoConfigureMockMvc
@Import(BookingMessagingIntegrationTest.Containers.class)
@Testcontainers(disabledWithoutDocker = true)
class BookingMessagingIntegrationTest {

    private static final String PROBE_QUEUE = "test.booking-events";

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {
        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>("postgres:16-alpine");
        }

        @Bean
        @ServiceConnection
        RabbitMQContainer rabbit() {
            return new RabbitMQContainer("rabbitmq:4.1-management-alpine");
        }
    }

    @MockitoBean
    RoomServiceClient rooms;
    @MockitoBean
    PaymentServiceClient payments;
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    RabbitTemplate rabbit;
    @Autowired
    AmqpAdmin admin;
    @Autowired
    JdbcTemplate jdbc;

    private final LocalDate checkIn = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(40);
    private final UUID roomId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private final UUID paymentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        reset(rooms, payments);
        when(rooms.hold(any())).thenAnswer(inv -> hold(((RoomServiceClient.HoldRequest) inv.getArgument(0)).bookingId(), "HELD"));
        when(payments.create(any())).thenAnswer(inv -> {
            PaymentServiceClient.CreatePayment req = inv.getArgument(0);
            return new PaymentServiceClient.Payment(paymentId, req.bookingId(), req.amount(), req.currency(), "CREATED",
                    "SIMULATED", true, null, BigDecimal.ZERO, req.expiresAt());
        });
        for (Declarable d : RabbitDeclarations.consumerQueue(PROBE_QUEUE, "booking.#").getDeclarables()) {
            if (d instanceof Queue q) {
                admin.declareQueue(q);
            } else if (d instanceof Exchange e) {
                admin.declareExchange(e);
            } else if (d instanceof Binding b) {
                admin.declareBinding(b);
            }
        }
        admin.purgeQueue(PROBE_QUEUE, false);
    }

    private RoomServiceClient.Hold hold(UUID bookingId, String status) {
        var night = new RoomServiceClient.NightlyRate(checkIn, new BigDecimal("4500.00"), BigDecimal.ONE, null, BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ONE, null, new BigDecimal("4500.00"));
        var quote = new RoomServiceClient.Quote("INR", checkIn, checkIn.plusDays(2), 2, List.of(night, night),
                new BigDecimal("9000.00"), new BigDecimal("450.00"), new BigDecimal("1134.00"), new BigDecimal("10584.00"),
                new BigDecimal("4500.00"), 1, Instant.now());
        return new RoomServiceClient.Hold(UUID.randomUUID(), bookingId, roomId, "101", "Classic Queen Room", "Classic Room",
                checkIn, checkIn.plusDays(2), 2, status, Instant.now().plus(15, ChronoUnit.MINUTES), null, quote);
    }

    private UUID createBooking() throws Exception {
        String body = """
                {"roomId":"%s","checkIn":"%s","checkOut":"%s","guests":2,
                 "primaryGuest":{"fullName":"Asha Rao","email":"asha@example.test"},"expectedTotal":10584.00}
                """.formatted(roomId, checkIn, checkIn.plusDays(2));
        String response = mvc.perform(post("/api/v1/bookings")
                        .with(jwt().jwt(j -> j.subject(customerId.toString()).claim("roles", List.of("CUSTOMER"))
                                .claim("email", "asha.account@example.test")).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                        .header("Idempotency-Key", "msg-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(response).get("id").asText());
    }

    private JsonNode nextEvent(String expectedType) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            Message m = rabbit.receive(PROBE_QUEUE, 1000);
            if (m != null && expectedType.equals(m.getMessageProperties().getType())) {
                JsonNode event = json.readTree(m.getBody());
                assertThat(m.getMessageProperties().getMessageId()).isEqualTo(event.get("eventId").asText());
                assertThat(m.getMessageProperties().getReceivedRoutingKey()).isEqualTo(EventTopology.routingKey(expectedType));
                return event;
            }
        }
        throw new AssertionError("no " + expectedType + " event published within 15 s");
    }

    private void publishPaymentEvent(UUID bookingId, UUID eventId) {
        String payload = """
                {"eventId":"%s","eventType":"PAYMENT_SUCCEEDED","schemaVersion":1,"paymentId":"%s","bookingId":"%s",
                 "status":"SUCCEEDED","amount":10584.00,"currency":"INR","failureCode":null,"provider":"SIMULATED",
                 "occurredAt":"%s"}
                """.formatted(eventId, paymentId, bookingId, Instant.now());
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setMessageId(eventId.toString());
        props.setType("PAYMENT_SUCCEEDED");
        rabbit.send(EventTopology.EXCHANGE, "payment.succeeded", new Message(payload.getBytes(StandardCharsets.UTF_8), props));
    }

    private String dbStatus(UUID bookingId) {
        return jdbc.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }

    @Test
    void bookingCreatedIsPublishedWithContactAndStayDetails() throws Exception {
        UUID id = createBooking();
        JsonNode event = nextEvent("BOOKING_CREATED");
        assertThat(event.get("bookingId").asText()).isEqualTo(id.toString());
        assertThat(event.get("customerEmail").asText()).isEqualTo("asha.account@example.test");
        assertThat(event.get("guestName").asText()).isEqualTo("Asha Rao");
        assertThat(event.get("roomName").asText()).isEqualTo("Classic Queen Room");
        assertThat(event.get("totalAmount").decimalValue()).isEqualByComparingTo("10584.00");
        assertThat(event.get("schemaVersion").asInt()).isEqualTo(1);
        // the relay marks the row SENT in the transaction that published it; it commits just after the broker confirm
        String outboxStatus = "";
        for (int i = 0; i < 50 && !"SENT".equals(outboxStatus); i++) {
            Thread.sleep(100);
            outboxStatus = jdbc.queryForObject("SELECT status FROM outbox_events WHERE aggregate_id = ? AND event_type = 'BOOKING_CREATED'",
                    String.class, id);
        }
        assertThat(outboxStatus).isEqualTo("SENT");
    }

    @Test
    void paymentSucceededFromTheBrokerConfirmsOnceAndPublishesBookingConfirmed() throws Exception {
        UUID id = createBooking();
        nextEvent("BOOKING_CREATED");
        when(rooms.confirm(id)).thenReturn(hold(id, "CONFIRMED"));
        UUID eventId = UUID.randomUUID();

        publishPaymentEvent(id, eventId);
        publishPaymentEvent(id, eventId); // duplicate delivery

        JsonNode confirmed = nextEvent("BOOKING_CONFIRMED");
        assertThat(confirmed.get("bookingId").asText()).isEqualTo(id.toString());
        assertThat(dbStatus(id)).isEqualTo("CONFIRMED");
        Thread.sleep(500);
        // Two consumers may process the duplicate concurrently; the room confirm call is idempotent, so it may be
        // repeated, but the booking transitions once, records the event once and publishes one BOOKING_CONFIRMED.
        verify(rooms, org.mockito.Mockito.atLeastOnce()).confirm(id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class, eventId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_status_history WHERE booking_id = ? AND to_status = 'CONFIRMED'",
                Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'BOOKING_CONFIRMED'",
                Integer.class, id)).isEqualTo(1);
    }

    @Test
    void malformedPaymentEventIsDeadLettered() {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setType("PAYMENT_SUCCEEDED");
        rabbit.send(EventTopology.EXCHANGE, "payment.succeeded", new Message("{not json".getBytes(StandardCharsets.UTF_8), props));

        Message dead = rabbit.receive("booking.payment-events.dlq", 10_000);
        assertThat(dead).isNotNull();
        assertThat(new String(dead.getBody(), StandardCharsets.UTF_8)).isEqualTo("{not json");
        verify(rooms, timeout(100).times(0)).confirm(any());
    }
}
