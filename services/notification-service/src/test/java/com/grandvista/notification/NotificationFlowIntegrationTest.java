package com.grandvista.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end within the service: events published to a real RabbitMQ broker become real e-mails in a real
 * Mailpit SMTP server (checked through Mailpit's HTTP API), exactly once per event, with retries and
 * dead-lettering for events that cannot be processed.
 */
@SpringBootTest(properties = {"eureka.client.enabled=false", "gv.security.jwt.jwk-set-uri=http://localhost:1/unused",
        "spring.rabbitmq.listener.simple.retry.initial-interval=100ms",
        "spring.rabbitmq.listener.simple.retry.max-interval=200ms",
        "spring.rabbitmq.listener.simple.retry.max-attempts=3"})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class NotificationFlowIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1-management-alpine");

    @Container
    static final GenericContainer<?> MAILPIT = new GenericContainer<>("axllent/mailpit:v1.27")
            .withExposedPorts(1025, 8025)
            .waitingFor(Wait.forHttp("/readyz").forPort(8025));

    @DynamicPropertySource
    static void mail(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", MAILPIT::getHost);
        registry.add("spring.mail.port", () -> MAILPIT.getMappedPort(1025));
    }

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    RabbitTemplate rabbit;
    @Autowired
    ObjectMapper json;
    @Autowired
    MockMvc mvc;

    private void publish(String routingKey, String type, String payload) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setType(type);
        props.setHeader("X-Correlation-Id", "notify-test-0001");
        rabbit.send("grandvista.events", routingKey, new Message(payload.getBytes(StandardCharsets.UTF_8), props));
    }

    private String bookingEvent(UUID eventId, String type, UUID bookingId, String reference, String email, String refund) {
        return """
                {"eventId":"%s","eventType":"%s","schemaVersion":1,"bookingId":"%s","reference":"%s",
                 "customerId":"%s","customerEmail":"%s","guestName":"Asha Rao","roomName":"Classic Queen Room",
                 "roomTypeName":"Classic Room","roomNumber":"101","checkIn":"2027-03-10","checkOut":"2027-03-12",
                 "nights":2,"guests":2,"totalAmount":10584.00,"currency":"INR","refundAmount":%s,
                 "holdExpiresAt":"2027-03-01T10:15:00Z","status":"X","reason":null,"occurredAt":"%s"}
                """.formatted(eventId, type, bookingId, reference, UUID.randomUUID(), email, refund, Instant.now());
    }

    private String paymentEvent(UUID eventId, String type, UUID bookingId) {
        return """
                {"eventId":"%s","eventType":"%s","schemaVersion":1,"paymentId":"%s","bookingId":"%s",
                 "status":"SUCCEEDED","amount":10584.00,"currency":"INR","failureCode":null,"provider":"SIMULATED",
                 "occurredAt":"%s"}
                """.formatted(eventId, type, UUID.randomUUID(), bookingId, Instant.now());
    }

    /** Mailpit search for messages to one recipient. */
    private JsonNode mailTo(String email) throws Exception {
        String query = URLEncoder.encode("to:\"" + email + "\"", StandardCharsets.UTF_8);
        var request = HttpRequest.newBuilder(URI.create("http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025)
                + "/api/v1/search?query=" + query)).GET().build();
        return json.readTree(HTTP.send(request, HttpResponse.BodyHandlers.ofString()).body()).path("messages");
    }

    private JsonNode awaitMail(String email, int count) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        JsonNode messages = mailTo(email);
        while (messages.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(250);
            messages = mailTo(email);
        }
        return messages;
    }

    private String html(String messageId) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025)
                + "/api/v1/message/" + messageId)).GET().build();
        return json.readTree(HTTP.send(request, HttpResponse.BodyHandlers.ofString()).body()).path("HTML").asText();
    }

    private List<String> subjects(JsonNode messages) {
        return java.util.stream.StreamSupport.stream(messages.spliterator(), false).map(m -> m.path("Subject").asText()).toList();
    }

    @Test
    void confirmationAndReceiptAreEmailedExactlyOnceEvenWhenEventsAreRedelivered() throws Exception {
        UUID bookingId = UUID.randomUUID();
        String email = "confirm-" + bookingId + "@example.test";
        UUID confirmedEvent = UUID.randomUUID();
        UUID paymentEvent = UUID.randomUUID();

        publish("booking.created", "BOOKING_CREATED", bookingEvent(UUID.randomUUID(), "BOOKING_CREATED", bookingId, "GV-TEST01", email, "null"));
        publish("payment.succeeded", "PAYMENT_SUCCEEDED", paymentEvent(paymentEvent, "PAYMENT_SUCCEEDED", bookingId));
        publish("booking.confirmed", "BOOKING_CONFIRMED", bookingEvent(confirmedEvent, "BOOKING_CONFIRMED", bookingId, "GV-TEST01", email, "null"));
        // duplicates (at-least-once delivery)
        publish("booking.confirmed", "BOOKING_CONFIRMED", bookingEvent(confirmedEvent, "BOOKING_CONFIRMED", bookingId, "GV-TEST01", email, "null"));
        publish("payment.succeeded", "PAYMENT_SUCCEEDED", paymentEvent(paymentEvent, "PAYMENT_SUCCEEDED", bookingId));

        JsonNode messages = awaitMail(email, 2);
        Thread.sleep(1500); // give any duplicate time to arrive
        messages = mailTo(email);
        assertThat(subjects(messages)).containsExactlyInAnyOrder("Your stay is confirmed — GV-TEST01", "Payment receipt — GV-TEST01");

        for (JsonNode m : messages) {
            String html = html(m.path("ID").asText());
            assertThat(html).contains("Asha Rao", "Classic Queen Room");
            if (m.path("Subject").asText().startsWith("Payment receipt")) {
                assertThat(html).contains("₹10,584.00", "simulated payment");
            } else {
                assertThat(html).contains("Wed, 10 Mar 2027", "Fri, 12 Mar 2027", "/account/bookings/" + bookingId);
            }
        }

        mvc.perform(get("/api/v1/admin/notifications").param("bookingId", bookingId.toString())
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("roles", List.of("STAFF")))
                                .authorities(new SimpleGrantedAuthority("ROLE_STAFF"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].status").value("SENT"));
        mvc.perform(get("/api/v1/admin/notifications")
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("roles", List.of("CUSTOMER")))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void cancellationAndExpiryEmailsExplainRefundAndRelease() throws Exception {
        UUID bookingId = UUID.randomUUID();
        String email = "cancel-" + bookingId + "@example.test";
        publish("booking.cancelled", "BOOKING_CANCELLED", bookingEvent(UUID.randomUUID(), "BOOKING_CANCELLED", bookingId, "GV-TEST02", email, "5292.00"));
        UUID other = UUID.randomUUID();
        publish("booking.hold-expired", "HOLD_EXPIRED", bookingEvent(UUID.randomUUID(), "HOLD_EXPIRED", other, "GV-TEST03", email, "null"));

        JsonNode messages = awaitMail(email, 2);
        assertThat(subjects(messages)).containsExactlyInAnyOrder("Your booking has been cancelled — GV-TEST02",
                "Your reservation hold has expired — GV-TEST03");
        for (JsonNode m : messages) {
            String html = html(m.path("ID").asText());
            if (m.path("Subject").asText().contains("cancelled")) {
                assertThat(html).contains("₹5,292.00");
            } else {
                assertThat(html).contains("You have not been charged");
            }
        }
    }

    @Test
    void paymentEventForAnUnknownBookingIsRetriedThenDeadLettered() {
        UUID eventId = UUID.randomUUID();
        publish("payment.succeeded", "PAYMENT_SUCCEEDED", paymentEvent(eventId, "PAYMENT_SUCCEEDED", UUID.randomUUID()));
        Message dead = rabbit.receive("notification.events.dlq", 15_000);
        assertThat(dead).isNotNull();
        assertThat(new String(dead.getBody(), StandardCharsets.UTF_8)).contains(eventId.toString());
    }

    @Test
    void malformedEventIsDeadLettered() {
        publish("booking.created", "BOOKING_CREATED", "{oops");
        Message dead = rabbit.receive("notification.events.dlq", 15_000);
        assertThat(dead).isNotNull();
        assertThat(new String(dead.getBody(), StandardCharsets.UTF_8)).isEqualTo("{oops");
    }
}
