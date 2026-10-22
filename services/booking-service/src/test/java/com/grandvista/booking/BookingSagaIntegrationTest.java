package com.grandvista.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grandvista.booking.client.DownstreamException;
import com.grandvista.booking.client.PaymentServiceClient;
import com.grandvista.booking.client.RoomServiceClient;
import com.grandvista.booking.domain.BookingStatus;
import com.grandvista.booking.repository.BookingRepository;
import com.grandvista.booking.service.BookingRecoveryJob;
import com.grandvista.platform.client.RemoteProblem;
import com.grandvista.platform.client.ServiceCallException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Exercises the booking saga against a real PostgreSQL database with the room- and payment-service
 * clients replaced by mocks at the HTTP-client boundary (their HTTP behaviour is covered by contract tests).
 */
@SpringBootTest(properties = {"eureka.client.enabled=false", "gv.security.jwt.jwk-set-uri=http://localhost:1/unused",
        "gv.booking.recovery.interval=1h"})
@AutoConfigureMockMvc
@Import(BookingSagaIntegrationTest.Containers.class)
@Testcontainers(disabledWithoutDocker = true)
class BookingSagaIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {
        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>("postgres:16-alpine");
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
    BookingRecoveryJob recovery;
    @Autowired
    BookingRepository bookings;
    @Autowired
    JdbcTemplate jdbc;

    private final LocalDate checkIn = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(30);
    private final UUID roomId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private final UUID paymentId = UUID.randomUUID();

    @BeforeEach
    void defaults() {
        reset(rooms, payments);
        when(rooms.hold(any())).thenAnswer(inv -> hold(((RoomServiceClient.HoldRequest) inv.getArgument(0)).bookingId(),
                "HELD", new BigDecimal("10584.00")));
        when(payments.create(any())).thenAnswer(inv -> payment(((PaymentServiceClient.CreatePayment) inv.getArgument(0)),
                "CREATED"));
    }

    private RoomServiceClient.Hold hold(UUID bookingId, String status, BigDecimal total) {
        var night = new RoomServiceClient.NightlyRate(checkIn, new BigDecimal("4500.00"), BigDecimal.ONE, null, BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ONE, null, new BigDecimal("4500.00"));
        var quote = new RoomServiceClient.Quote("INR", checkIn, checkIn.plusDays(2), 2, List.of(night, night),
                new BigDecimal("9000.00"), new BigDecimal("450.00"), total.subtract(new BigDecimal("9450.00")), total,
                new BigDecimal("4500.00"), 1, Instant.now());
        return new RoomServiceClient.Hold(UUID.randomUUID(), bookingId, roomId, "101", "Classic Queen Room", "Classic Room",
                checkIn, checkIn.plusDays(2), 2, status, Instant.now().plus(15, ChronoUnit.MINUTES), null, quote);
    }

    private PaymentServiceClient.Payment payment(PaymentServiceClient.CreatePayment req, String status) {
        return new PaymentServiceClient.Payment(paymentId, req.bookingId(), req.amount(), req.currency(), status,
                "SIMULATED", true, null, BigDecimal.ZERO, req.expiresAt());
    }

    private JwtRequestPostProcessor customer(UUID id) {
        return jwt().jwt(j -> j.subject(id.toString()).claim("roles", List.of("CUSTOMER")).claim("email", "guest@example.test"))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private JwtRequestPostProcessor personnel(String role) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("roles", List.of(role)))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private JwtRequestPostProcessor service() {
        return jwt().jwt(j -> j.subject("service:payment-service").claim("roles", List.of("SERVICE")))
                .authorities(new SimpleGrantedAuthority("ROLE_SERVICE"));
    }

    private String body(String expectedTotal) {
        return """
                {"roomId":"%s","checkIn":"%s","checkOut":"%s","guests":2,
                 "primaryGuest":{"fullName":"Asha Rao","email":"asha@example.test","phone":"+91 98765 43210"},
                 "specialRequests":"Late arrival"%s}
                """.formatted(roomId, checkIn, checkIn.plusDays(2),
                expectedTotal == null ? "" : ",\"expectedTotal\":" + expectedTotal);
    }

    private ResultActions create(String key, String body) throws Exception {
        return mvc.perform(post("/api/v1/bookings").with(customer(customerId)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode createOk(String key) throws Exception {
        return json.readTree(create(key, body("10584.00")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    private ResultActions event(UUID bookingId, String type, UUID eventId) throws Exception {
        return mvc.perform(post("/internal/v1/payment-events").with(service()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"eventId":"%s","eventType":"%s","schemaVersion":1,"paymentId":"%s","bookingId":"%s",
                         "status":"%s","amount":10584.00,"currency":"INR","failureCode":%s,"occurredAt":"%s"}
                        """.formatted(eventId, type, paymentId, bookingId,
                        type.equals("PAYMENT_SUCCEEDED") ? "SUCCEEDED" : "FAILED",
                        type.equals("PAYMENT_SUCCEEDED") ? "null" : "\"card_declined\"", Instant.now())));
    }

    private String dbStatus(UUID bookingId) {
        return jdbc.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }

    @Test
    void happyPathCreatesPendingBookingWithSnapshotThenConfirmsOnPaymentEvent() throws Exception {
        JsonNode booking = createOk("key-happy-path-1");
        UUID id = UUID.fromString(booking.get("id").asText());
        assertThat(booking.get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(booking.get("reference").asText()).matches("^GV-[2-9A-HJ-NP-Z]{6}$");
        assertThat(booking.get("price").get("total").decimalValue()).isEqualByComparingTo("10584.00");
        assertThat(booking.get("price").get("nightlyRates")).hasSize(2);
        assertThat(booking.get("paymentId").asText()).isEqualTo(paymentId.toString());
        assertThat(booking.get("allowedActions").toString()).contains("PAY");

        // the payment is created for exactly the snapshot amount
        verify(payments).create(org.mockito.ArgumentMatchers.argThat(p -> p.amount().compareTo(new BigDecimal("10584.00")) == 0
                && p.bookingId().equals(id) && p.customerId().equals(customerId)));

        when(rooms.confirm(id)).thenReturn(hold(id, "CONFIRMED", new BigDecimal("10584.00")));
        UUID eventId = UUID.randomUUID();
        event(id, "PAYMENT_SUCCEEDED", eventId).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("APPLIED"));
        event(id, "PAYMENT_SUCCEEDED", eventId).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("DUPLICATE"));
        verify(rooms, times(1)).confirm(id);

        mvc.perform(get("/api/v1/bookings/{id}/status", id).with(customer(customerId)))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        mvc.perform(get("/api/v1/bookings/{id}/history", id).with(customer(customerId)))
                .andExpect(jsonPath("$[*].toStatus").value(org.hamcrest.Matchers.contains("CREATED", "PENDING_PAYMENT", "CONFIRMED")));

        // transactional outbox: one event per published transition, written with the state change
        assertThat(jdbc.queryForList("SELECT event_type FROM outbox_events WHERE aggregate_id = ? ORDER BY created_at",
                String.class, id)).containsExactly("BOOKING_CREATED", "BOOKING_CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT payload->>'customerEmail' FROM outbox_events WHERE aggregate_id = ? "
                + "AND event_type = 'BOOKING_CONFIRMED'", String.class, id)).isEqualTo("guest@example.test");

        // provider side of contracts/booking-service/booking-event.json: every published field is present
        JsonNode example = json.readTree(java.nio.file.Files.readString(
                java.nio.file.Path.of("../../contracts/booking-service/booking-event.json")));
        JsonNode actual = json.readTree(jdbc.queryForObject("SELECT payload::text FROM outbox_events WHERE aggregate_id = ? "
                + "AND event_type = 'BOOKING_CONFIRMED'", String.class, id));
        example.fieldNames().forEachRemaining(field -> assertThat(actual.has(field)).as("contract field %s", field).isTrue());
    }

    @Test
    void retriesWithSameKeyReturnTheSameBookingAndDifferentBodyIsRejected() throws Exception {
        JsonNode first = createOk("key-idempotent-1");
        create("key-idempotent-1", body("10584.00")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(first.get("id").asText()));
        create("key-idempotent-1", body("99.00")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        verify(rooms, times(1)).hold(any());
        verify(payments, times(1)).create(any());
        Integer count = jdbc.queryForObject("SELECT count(*) FROM bookings WHERE idempotency_key = 'key-idempotent-1'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void unavailableRoomIsRejectedWithoutPayment() throws Exception {
        doThrow(new DownstreamException("room-service", new RemoteProblem(409, "ROOM_UNAVAILABLE", "taken", Map.of())))
                .when(rooms).hold(any());
        String response = create("key-unavailable-1", body(null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROOM_UNAVAILABLE"))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(response).get("bookingId").asText());
        assertThat(dbStatus(id)).isEqualTo("REJECTED");
        verify(payments, never()).create(any());
    }

    @Test
    void changedPriceIsRejectedAndHoldReleased() throws Exception {
        create("key-price-changed", body("9999.00")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRICE_CHANGED"))
                .andExpect(jsonPath("$.currentTotal").value(10584.00));
        verify(rooms).release(any(), eq("PRICE_CHANGED"));
        verify(payments, never()).create(any());
    }

    @Test
    void outageLeavesBookingCreatedThenRetryResumesAndRecoveryCleansUpAbandonedOnes() throws Exception {
        doThrow(new ServiceCallException("payment-service", "down", null)).when(payments).create(any());
        String response = create("key-outage-0001", body(null)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PAYMENT_SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(response).get("bookingId").asText());
        assertThat(dbStatus(id)).isEqualTo("CREATED");

        // the same request retried after the outage resumes the saga
        reset(payments);
        when(payments.create(any())).thenAnswer(inv -> payment(inv.getArgument(0), "CREATED"));
        create("key-outage-0001", body(null)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));

        // an abandoned one is expired by recovery, releasing the hold
        doThrow(new ServiceCallException("payment-service", "down", null)).when(payments).create(any());
        String abandoned = create("key-outage-0002", body(null)).andReturn().getResponse().getContentAsString();
        UUID abandonedId = UUID.fromString(json.readTree(abandoned).get("bookingId").asText());
        when(payments.getByBooking(abandonedId)).thenThrow(new DownstreamException("payment-service",
                new RemoteProblem(404, "PAYMENT_NOT_FOUND", "none", Map.of())));
        recovery.recoverCreated(abandonedId);
        assertThat(dbStatus(abandonedId)).isEqualTo("EXPIRED");
        verify(rooms).release(abandonedId, "BOOKING_NOT_COMPLETED");
    }

    @Test
    void failedPaymentReleasesInventory() throws Exception {
        UUID id = UUID.fromString(createOk("key-pay-failed-1").get("id").asText());
        event(id, "PAYMENT_FAILED", UUID.randomUUID()).andExpect(status().isOk());
        assertThat(dbStatus(id)).isEqualTo("PAYMENT_FAILED");
        verify(rooms).release(id, "PAYMENT_FAILED");
    }

    @Test
    void paymentSucceededButRoomLostIsCompensatedWithFullRefund() throws Exception {
        UUID id = UUID.fromString(createOk("key-room-lost-01").get("id").asText());
        when(rooms.confirm(id)).thenThrow(new DownstreamException("room-service",
                new RemoteProblem(409, "INVENTORY_NO_LONGER_AVAILABLE", "gone", Map.of())));
        when(payments.refund(eq(paymentId), any())).thenReturn(
                new PaymentServiceClient.Refund(UUID.randomUUID(), paymentId, new BigDecimal("10584.00"), "SUCCEEDED", null));

        event(id, "PAYMENT_SUCCEEDED", UUID.randomUUID()).andExpect(status().isOk());

        assertThat(dbStatus(id)).isEqualTo("CANCELLED");
        verify(payments).refund(eq(paymentId), org.mockito.ArgumentMatchers.argThat(r ->
                r.amount().compareTo(new BigDecimal("10584.00")) == 0 && r.idempotencyKey().equals("refund-" + id)));
    }

    @Test
    void transientFailureWhileConfirmingReturns503SoTheEventIsRedelivered() throws Exception {
        UUID id = UUID.fromString(createOk("key-redelivery-1").get("id").asText());
        UUID eventId = UUID.randomUUID();
        when(rooms.confirm(id)).thenThrow(new ServiceCallException("room-service", "down", null));
        event(id, "PAYMENT_SUCCEEDED", eventId).andExpect(status().isServiceUnavailable());
        assertThat(dbStatus(id)).isEqualTo("PENDING_PAYMENT");

        reset(rooms);
        when(rooms.confirm(id)).thenReturn(hold(id, "CONFIRMED", new BigDecimal("10584.00")));
        event(id, "PAYMENT_SUCCEEDED", eventId).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("APPLIED"));
        assertThat(dbStatus(id)).isEqualTo("CONFIRMED");
    }

    @Test
    void confirmedBookingCancellationRefundsPerPolicy() throws Exception {
        UUID id = UUID.fromString(createOk("key-cancel-00001").get("id").asText());
        when(rooms.confirm(id)).thenReturn(hold(id, "CONFIRMED", new BigDecimal("10584.00")));
        event(id, "PAYMENT_SUCCEEDED", UUID.randomUUID()).andExpect(status().isOk());
        when(payments.refund(eq(paymentId), any())).thenReturn(
                new PaymentServiceClient.Refund(UUID.randomUUID(), paymentId, new BigDecimal("10584.00"), "SUCCEEDED", null));

        mvc.perform(get("/api/v1/bookings/{id}/cancellation-quote", id).with(customer(customerId)))
                .andExpect(jsonPath("$.refundPercent").value(100))
                .andExpect(jsonPath("$.rule").value("FULL_REFUND"));
        mvc.perform(post("/api/v1/bookings/{id}/cancel", id).with(customer(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/bookings/{id}/cancel", id).with(customer(customerId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Change of plans\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.refundAmount").value(10584.00));
        verify(rooms).release(id, "CANCELLED");
        // cancelling again is idempotent
        mvc.perform(post("/api/v1/bookings/{id}/cancel", id).with(customer(customerId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        verify(payments, times(1)).refund(any(), any());
    }

    @Test
    void unpaidBookingPastHoldExpiryIsExpiredOrConfirmedIfPaymentActuallySucceeded() throws Exception {
        UUID expired = UUID.fromString(createOk("key-expiry-00001").get("id").asText());
        UUID lostEvent = UUID.fromString(createOk("key-expiry-00002").get("id").asText());
        jdbc.update("UPDATE bookings SET hold_expires_at = now() - interval '5 minutes' WHERE id IN (?, ?)", expired, lostEvent);

        when(payments.cancel(paymentId, "BOOKING_EXPIRED"))
                .thenReturn(new PaymentServiceClient.Payment(paymentId, expired, BigDecimal.TEN, "INR", "CANCELLED",
                        "SIMULATED", true, null, BigDecimal.ZERO, Instant.now()));
        recovery.expireUnpaid(expired);
        assertThat(dbStatus(expired)).isEqualTo("EXPIRED");
        verify(rooms).release(expired, "PAYMENT_WINDOW_ELAPSED");

        reset(payments);
        when(payments.cancel(paymentId, "BOOKING_EXPIRED")).thenThrow(new DownstreamException("payment-service",
                new RemoteProblem(409, "PAYMENT_ALREADY_CAPTURED", "paid", Map.of("paymentStatus", "SUCCEEDED"))));
        when(rooms.confirm(lostEvent)).thenReturn(hold(lostEvent, "CONFIRMED", new BigDecimal("10584.00")));
        recovery.expireUnpaid(lostEvent);
        assertThat(dbStatus(lostEvent)).isEqualTo("CONFIRMED");
    }

    @Test
    void searchesWorkWithAndWithoutEachOptionalFilter() throws Exception {
        UUID id = UUID.fromString(createOk("key-search-00001").get("id").asText());
        for (String scope : new String[] {"UPCOMING", "PAST", "ALL"}) {
            mvc.perform(get("/api/v1/bookings/my").param("scope", scope).with(customer(customerId)))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/api/v1/bookings/my").param("scope", "UPCOMING").with(customer(customerId)))
                .andExpect(jsonPath("$.content[*].id", org.hamcrest.Matchers.hasItem(id.toString())));
        mvc.perform(get("/api/v1/bookings/my").param("scope", "PAST").with(customer(customerId)))
                .andExpect(jsonPath("$.totalElements").value(0));

        JwtRequestPostProcessor admin = personnel("ADMIN");
        mvc.perform(get("/api/v1/admin/bookings").with(admin)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/bookings").param("status", "PENDING_PAYMENT").with(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[*].id", org.hamcrest.Matchers.hasItem(id.toString())));
        mvc.perform(get("/api/v1/admin/bookings").param("from", checkIn.toString()).param("to", checkIn.plusDays(1).toString())
                .with(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.content[*].id", org.hamcrest.Matchers.hasItem(id.toString())));
        mvc.perform(get("/api/v1/admin/bookings").param("from", checkIn.plusDays(10).toString()).with(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[*].id", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(id.toString()))));
        String reference = jdbc.queryForObject("SELECT reference FROM bookings WHERE id = ?", String.class, id);
        mvc.perform(get("/api/v1/admin/bookings").param("q", reference.toLowerCase()).with(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void recoveryWaitsWhilePaymentIsInProgressAndIsIdempotent() throws Exception {
        UUID id = UUID.fromString(createOk("key-recovery-0001").get("id").asText());
        jdbc.update("UPDATE bookings SET hold_expires_at = now() - interval '5 minutes' WHERE id = ?", id);

        // payment still being processed by the provider: nothing is released, the booking waits
        doThrow(new DownstreamException("payment-service", new RemoteProblem(409, "PAYMENT_IN_PROGRESS", "busy", Map.of())))
                .when(payments).cancel(paymentId, "BOOKING_EXPIRED");
        recovery.expireUnpaid(id);
        assertThat(dbStatus(id)).isEqualTo("PENDING_PAYMENT");
        verify(rooms, never()).release(eq(id), any());

        // later the provider settles without a charge: voided, released, expired — and re-running changes nothing
        reset(payments);
        when(payments.cancel(paymentId, "BOOKING_EXPIRED")).thenReturn(new PaymentServiceClient.Payment(paymentId, id,
                BigDecimal.TEN, "INR", "CANCELLED", "SIMULATED", true, null, BigDecimal.ZERO, Instant.now()));
        recovery.expireUnpaid(id);
        recovery.expireUnpaid(id);
        assertThat(dbStatus(id)).isEqualTo("EXPIRED");
        verify(payments, times(1)).cancel(paymentId, "BOOKING_EXPIRED");
        verify(rooms, times(1)).release(id, "PAYMENT_WINDOW_ELAPSED");
    }

    @Test
    void latePaymentSuccessForAnExpiredBookingIsRefundedNeverConfirmed() throws Exception {
        UUID id = UUID.fromString(createOk("key-late-pay-0001").get("id").asText());
        jdbc.update("UPDATE bookings SET hold_expires_at = now() - interval '5 minutes' WHERE id = ?", id);
        when(payments.cancel(paymentId, "BOOKING_EXPIRED")).thenReturn(new PaymentServiceClient.Payment(paymentId, id,
                BigDecimal.TEN, "INR", "CANCELLED", "SIMULATED", true, null, BigDecimal.ZERO, Instant.now()));
        recovery.expireUnpaid(id);
        assertThat(dbStatus(id)).isEqualTo("EXPIRED");

        when(payments.refund(eq(paymentId), any())).thenReturn(
                new PaymentServiceClient.Refund(UUID.randomUUID(), paymentId, new BigDecimal("10584.00"), "SUCCEEDED", null));
        UUID eventId = UUID.randomUUID();
        event(id, "PAYMENT_SUCCEEDED", eventId).andExpect(status().isOk());
        event(id, "PAYMENT_SUCCEEDED", eventId).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("DUPLICATE"));

        assertThat(dbStatus(id)).isEqualTo("EXPIRED");
        verify(rooms, never()).confirm(id);
        verify(payments, times(1)).refund(eq(paymentId), org.mockito.ArgumentMatchers.argThat(r ->
                r.amount().compareTo(new BigDecimal("10584.00")) == 0 && r.idempotencyKey().equals("refund-orphan-" + id)));
    }

    @Test
    void bookingHistoryIsPrivatePaginatedAndShowsFinalStatuses() throws Exception {
        UUID owner = customerId;
        UUID a = UUID.fromString(createOk("key-history-0001").get("id").asText());
        UUID b = UUID.fromString(createOk("key-history-0002").get("id").asText());
        UUID c = UUID.fromString(createOk("key-history-0003").get("id").asText());
        jdbc.update("UPDATE bookings SET status = 'CANCELLED' WHERE id = ?", b);
        jdbc.update("UPDATE bookings SET status = 'EXPIRED' WHERE id = ?", c);

        // another customer sees none of them, and gets a clean empty page
        mvc.perform(get("/api/v1/bookings/my").param("scope", "ALL").with(customer(UUID.randomUUID())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content").isEmpty());

        // pagination
        mvc.perform(get("/api/v1/bookings/my").param("scope", "ALL").param("size", "2").param("page", "0").with(customer(owner)))
                .andExpect(jsonPath("$.content.length()").value(2)).andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(get("/api/v1/bookings/my").param("scope", "ALL").param("size", "2").param("page", "1").with(customer(owner)))
                .andExpect(jsonPath("$.content.length()").value(1));
        mvc.perform(get("/api/v1/bookings/my").param("size", "0").with(customer(owner))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/bookings/my").param("scope", "BOGUS").with(customer(owner))).andExpect(status().isBadRequest());

        // statuses are reported as stored; terminal bookings offer no actions
        String all = mvc.perform(get("/api/v1/bookings/my").param("scope", "ALL").with(customer(owner)))
                .andReturn().getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode content = json.readTree(all).get("content");
        java.util.Map<String, String> statusById = new java.util.HashMap<>();
        content.forEach(n -> statusById.put(n.get("id").asText(), n.get("status").asText()));
        assertThat(statusById).containsEntry(a.toString(), "PENDING_PAYMENT").containsEntry(b.toString(), "CANCELLED")
                .containsEntry(c.toString(), "EXPIRED");
        content.forEach(n -> {
            if (!n.get("status").asText().equals("PENDING_PAYMENT")) {
                assertThat(n.get("allowedActions")).isEmpty();
            }
        });
    }

    @Test
    void accessRulesAndValidation() throws Exception {
        UUID id = UUID.fromString(createOk("key-access-00001").get("id").asText());
        mvc.perform(get("/api/v1/bookings/{id}", id).with(customer(UUID.randomUUID()))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/bookings/{id}", id).with(personnel("STAFF"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/bookings/{id}", id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/bookings").with(customer(customerId))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/bookings/summary").with(personnel("ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.last30Days.length()").value(30));
        mvc.perform(post("/internal/v1/payment-events").with(customer(customerId)).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/bookings/my").with(customer(customerId))).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(id.toString()));

        create("key-missing-guest", "{\"roomId\":\"" + roomId + "\",\"checkIn\":\"" + checkIn + "\",\"checkOut\":\""
                + checkIn.plusDays(1) + "\",\"guests\":0}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        create("key-bad-dates-01", body(null).replace("\"checkOut\":\"" + checkIn.plusDays(2), "\"checkOut\":\"" + checkIn))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
        mvc.perform(post("/api/v1/bookings").with(customer(customerId)).contentType(MediaType.APPLICATION_JSON)
                .content(body(null))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/bookings").with(personnel("ADMIN")).header("Idempotency-Key", "key-admin-booking")
                .contentType(MediaType.APPLICATION_JSON).content(body(null))).andExpect(status().isForbidden());
        assertThat(bookings.findById(id)).get().extracting(b -> b.getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
    }

    // ---------- cancellation edge cases ----------

    private UUID confirmedBooking(String key) throws Exception {
        UUID id = UUID.fromString(createOk(key).get("id").asText());
        when(rooms.confirm(id)).thenReturn(hold(id, "CONFIRMED", new BigDecimal("10584.00")));
        event(id, "PAYMENT_SUCCEEDED", UUID.randomUUID()).andExpect(status().isOk());
        return id;
    }

    private ResultActions cancel(UUID id, JwtRequestPostProcessor who) throws Exception {
        return mvc.perform(post("/api/v1/bookings/{id}/cancel", id).with(who).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"plans changed\"}"));
    }

    private PaymentServiceClient.Refund refund(String status) {
        return new PaymentServiceClient.Refund(UUID.randomUUID(), paymentId, new BigDecimal("10584.00"), status,
                "FAILED".equals(status) ? "card network declined" : null);
    }

    @Test
    void cancellingAnUnpaidBookingVoidsThePaymentThenReleasesTheRoom() throws Exception {
        UUID id = UUID.fromString(createOk("key-cancel-unpaid-1").get("id").asText());
        when(payments.cancel(eq(paymentId), any())).thenReturn(payment(new PaymentServiceClient.CreatePayment(id, "GV-X",
                customerId, new BigDecimal("10584.00"), "INR", Instant.now()), "CANCELLED"));

        cancel(id, customer(customerId)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        verify(rooms).release(id, "CANCELLED_BEFORE_PAYMENT");
        assertThat(dbStatus(id)).isEqualTo("CANCELLED");
        verify(payments, never()).refund(any(), any());
    }

    @Test
    void anUnpaidBookingWhosePaymentIsInProgressCannotBeCancelled() throws Exception {
        UUID id = UUID.fromString(createOk("key-cancel-inprog-1").get("id").asText());
        when(payments.cancel(eq(paymentId), any())).thenThrow(new DownstreamException("payment-service",
                new RemoteProblem(409, "PAYMENT_IN_PROGRESS", "in progress", Map.of())));

        cancel(id, customer(customerId)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_IN_PROGRESS"));
        assertThat(dbStatus(id)).isEqualTo("PENDING_PAYMENT");
        verify(rooms, never()).release(eq(id), any());
    }

    @Test
    void failedRefundNeedsAttentionAndAnAdministratorCanRetryIt() throws Exception {
        UUID id = confirmedBooking("key-cancel-refund-fail-1");
        when(payments.refund(eq(paymentId), any())).thenReturn(refund("FAILED"));

        cancel(id, customer(customerId)).andExpect(status().isOk());
        assertThat(dbStatus(id)).isEqualTo("NEEDS_ATTENTION");

        mvc.perform(post("/api/v1/admin/bookings/{id}/retry-compensation", id).with(personnel("STAFF")))
                .andExpect(status().isForbidden());
        when(payments.refund(eq(paymentId), any())).thenReturn(refund("SUCCEEDED"));
        mvc.perform(post("/api/v1/admin/bookings/{id}/retry-compensation", id).with(personnel("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post("/api/v1/admin/bookings/{id}/retry-compensation", id).with(personnel("ADMIN")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_NEEDING_ATTENTION"));
        // the same refund key is used on every attempt, so the payment-service can never refund twice
        verify(payments, times(2)).refund(eq(paymentId), org.mockito.ArgumentMatchers.argThat(
                r -> r.idempotencyKey().equals("refund-" + id)));
    }

    @Test
    void pendingRefundKeepsTheCancellationOpenUntilTheRefundSucceeds() throws Exception {
        UUID id = confirmedBooking("key-cancel-refund-pending-1");
        when(payments.refund(eq(paymentId), any())).thenReturn(refund("PENDING"));
        cancel(id, customer(customerId)).andExpect(status().isAccepted()); // 202: refund still in progress
        assertThat(dbStatus(id)).isEqualTo("CANCELLATION_PENDING");

        when(payments.refund(eq(paymentId), any())).thenReturn(refund("SUCCEEDED"));
        cancel(id, customer(customerId)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void hotelInitiatedCancellationRefundsInFull() throws Exception {
        UUID id = confirmedBooking("key-cancel-hotel-1");
        when(payments.refund(eq(paymentId), any())).thenReturn(refund("SUCCEEDED"));
        cancel(id, personnel("STAFF")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(jdbc.queryForObject("SELECT refund_amount FROM bookings WHERE id = ?", BigDecimal.class, id))
                .isEqualByComparingTo("10584.00");
    }
}
