package com.grandvista.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grandvista.payment.config.WebhookSecretHolder;
import com.grandvista.payment.domain.OutboxEvent;
import com.grandvista.payment.provider.WebhookSignature;
import com.grandvista.payment.repository.OutboxEventRepository;
import com.grandvista.payment.service.PaymentReconciliationJob;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {"eureka.client.enabled=false", "gv.security.jwt.jwk-set-uri=http://localhost:1/unused",
        "gv.payment.simulator.dispatch-interval=1h", "gv.payment.outbox.relay-interval=1h",
        "gv.payment.reconciliation.interval=1h", "gv.payment.webhook-secret=test-webhook-secret-0123456789abcdef"})
@AutoConfigureMockMvc
@Import(PaymentFlowIntegrationTest.Containers.class)
@Testcontainers(disabledWithoutDocker = true)
class PaymentFlowIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {
        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>("postgres:16-alpine");
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    OutboxEventRepository outbox;
    @Autowired
    WebhookSecretHolder secret;
    @Autowired
    PaymentReconciliationJob reconciliation;
    @Autowired
    JdbcTemplate jdbc;

    private static JwtRequestPostProcessor service() {
        return jwt().jwt(j -> j.subject("service:booking-service").claim("roles", List.of("SERVICE")))
                .authorities(new SimpleGrantedAuthority("ROLE_SERVICE"));
    }

    private static JwtRequestPostProcessor customer(UUID id) {
        return jwt().jwt(j -> j.subject(id.toString()).claim("roles", List.of("CUSTOMER")))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private static JwtRequestPostProcessor personnel(String role) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("roles", List.of(role)))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private record Created(UUID paymentId, UUID bookingId, UUID customerId) {
    }

    private Created createPayment(String amount, Instant expiresAt) throws Exception {
        UUID booking = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        String body = """
                {"bookingId":"%s","bookingReference":"GV-%s","customerId":"%s","amount":%s,"currency":"INR","expiresAt":"%s"}
                """.formatted(booking, booking.toString().substring(0, 6).toUpperCase(), customer, amount, expiresAt);
        String response = mvc.perform(post("/internal/v1/payments").with(service()).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.simulated").value(true))
                .andReturn().getResponse().getContentAsString();
        return new Created(UUID.fromString(json.readTree(response).get("id").asText()), booking, customer);
    }

    private Created createPayment() throws Exception {
        return createPayment("10584.00", Instant.now().plus(15, ChronoUnit.MINUTES));
    }

    private ResultActions confirm(Created p, String key, String token) throws Exception {
        return mvc.perform(post("/api/v1/payments/{id}/confirm", p.paymentId()).with(customer(p.customerId()))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentMethodToken\":\"" + token + "\"}"));
    }

    private List<OutboxEvent> events(UUID paymentId) {
        return outbox.findByAggregateIdOrderByCreatedAtAsc(paymentId);
    }

    @Test
    void creationIsIdempotentPerBookingAndRejectsDifferentTerms() throws Exception {
        Created p = createPayment();
        String same = """
                {"bookingId":"%s","bookingReference":"GV-AAAAAA","customerId":"%s","amount":10584.00,"currency":"INR","expiresAt":"%s"}
                """.formatted(p.bookingId(), p.customerId(), Instant.now().plus(10, ChronoUnit.MINUTES));
        mvc.perform(post("/internal/v1/payments").with(service()).contentType(MediaType.APPLICATION_JSON).content(same))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(p.paymentId().toString()));
        mvc.perform(post("/internal/v1/payments").with(service()).contentType(MediaType.APPLICATION_JSON)
                        .content(same.replace("10584.00", "1.00")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT_TERMS_MISMATCH"));
    }

    @Test
    void successfulPaymentIsIdempotentAndEmitsExactlyOneEvent() throws Exception {
        Created p = createPayment();
        confirm(p, "key-success-0001", "sim_card_success").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.providerReference").isNotEmpty());
        // same key: replay returns the same outcome, no second charge or event
        confirm(p, "key-success-0001", "sim_card_success").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
        // a new key cannot pay twice
        confirm(p, "key-success-0002", "sim_card_success").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_ALREADY_PROCESSED"));

        assertThat(events(p.paymentId())).extracting(OutboxEvent::getEventType).containsExactly("PAYMENT_SUCCEEDED");
        Integer charges = jdbc.queryForObject("SELECT count(*) FROM simulated_provider.charges WHERE idempotency_key = ?",
                Integer.class, p.paymentId().toString());
        assertThat(charges).isEqualTo(1);
    }

    @Test
    void declinedPaymentFailsAndEmitsFailureEvent() throws Exception {
        Created p = createPayment();
        confirm(p, "key-decline-0001", "sim_card_declined").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value("card_declined"));
        assertThat(events(p.paymentId())).extracting(OutboxEvent::getEventType).containsExactly("PAYMENT_FAILED");
    }

    @Test
    void customersCannotSeeOrPayOthersPayments() throws Exception {
        Created p = createPayment();
        mvc.perform(get("/api/v1/payments/{id}", p.paymentId()).with(customer(UUID.randomUUID())))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/payments/{id}/confirm", p.paymentId()).with(customer(UUID.randomUUID()))
                        .header("Idempotency-Key", "key-intruder-01").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentMethodToken\":\"sim_card_success\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/payments/{id}", p.paymentId()).with(customer(p.customerId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.payment.status").value("CREATED"));
        mvc.perform(get("/api/v1/payments/{id}", p.paymentId()).with(personnel("STAFF"))).andExpect(status().isOk());
    }

    @Test
    void clientCannotSupplyAmountOrFakeSuccessAndNeedsIdempotencyKey() throws Exception {
        Created p = createPayment();
        mvc.perform(post("/api/v1/payments/{id}/confirm", p.paymentId()).with(customer(p.customerId()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"paymentMethodToken\":\"sim_card_success\"}"))
                .andExpect(status().isBadRequest());
        // client-supplied amount/status fields are ignored: the amount fixed by the booking is charged
        mvc.perform(post("/api/v1/payments/{id}/confirm", p.paymentId()).with(customer(p.customerId()))
                        .header("Idempotency-Key", "key-extra-fields").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentMethodToken\":\"sim_card_declined\",\"amount\":1.00,\"status\":\"SUCCEEDED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(10584.00))
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    void expiredPaymentWindowIsRejected() throws Exception {
        Created p = createPayment("500.00", Instant.now().plusSeconds(2));
        Thread.sleep(2500);
        confirm(p, "key-expired-0001", "sim_card_success").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_EXPIRED"));
    }

    @Test
    void asyncPaymentIsCompletedBySignedWebhookAndDuplicatesAreIgnored() throws Exception {
        Created p = createPayment();
        String providerRef = json.readTree(confirm(p, "key-async-00001", "sim_card_async_success")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString()).get("providerReference").asText();

        byte[] body = """
                {"id":"evt_%s","type":"charge.succeeded","created":%d,
                 "data":{"reference":"%s","status":"SUCCEEDED","metadata":{"paymentId":"%s"}}}
                """.formatted(UUID.randomUUID(), Instant.now().getEpochSecond(), providerRef, p.paymentId())
                .getBytes(StandardCharsets.UTF_8);
        String signature = WebhookSignature.sign(secret.secret(), Instant.now().getEpochSecond(), body);

        mvc.perform(post("/api/v1/payments/webhooks/simulated").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/payments/webhooks/simulated").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header(WebhookSignature.HEADER, WebhookSignature.sign("wrong-secret-wrong-secret-wrong!!",
                                Instant.now().getEpochSecond(), body)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/payments/webhooks/simulated").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header(WebhookSignature.HEADER, WebhookSignature.sign(secret.secret(),
                                Instant.now().minusSeconds(3600).getEpochSecond(), body)))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/v1/payments/webhooks/simulated").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header(WebhookSignature.HEADER, signature))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("PROCESSED"));
        mvc.perform(post("/api/v1/payments/webhooks/simulated").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header(WebhookSignature.HEADER, signature))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("DUPLICATE"));

        mvc.perform(get("/api/v1/payments/{id}", p.paymentId()).with(customer(p.customerId())))
                .andExpect(jsonPath("$.payment.status").value("SUCCEEDED"));
        assertThat(events(p.paymentId())).extracting(OutboxEvent::getEventType).containsExactly("PAYMENT_SUCCEEDED");
    }

    @Test
    void lostProviderResponseIsResolvedByReconciliation() throws Exception {
        Created p = createPayment();
        confirm(p, "key-timeout-0001", "sim_card_timeout_success").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        reconciliation.reconcile(p.paymentId());
        mvc.perform(get("/api/v1/payments/{id}", p.paymentId()).with(customer(p.customerId())))
                .andExpect(jsonPath("$.payment.status").value("SUCCEEDED"));
        assertThat(events(p.paymentId())).extracting(OutboxEvent::getEventType).containsExactly("PAYMENT_SUCCEEDED");
    }

    @Test
    void cancelVoidsOnlyUnpaidPayments() throws Exception {
        Created unpaid = createPayment();
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/internal/v1/payments/{id}/cancel", unpaid.paymentId()).with(service())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"BOOKING_EXPIRED\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        }
        confirm(unpaid, "key-after-cancel", "sim_card_success").andExpect(status().isConflict());

        Created paid = createPayment();
        confirm(paid, "key-paid-000001", "sim_card_success").andExpect(status().isOk());
        mvc.perform(post("/internal/v1/payments/{id}/cancel", paid.paymentId()).with(service())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"BOOKING_EXPIRED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_ALREADY_CAPTURED"))
                .andExpect(jsonPath("$.paymentStatus").value("SUCCEEDED"));
    }

    @Test
    void refundsArePartialOrFullIdempotentAndBounded() throws Exception {
        Created p = createPayment("1000.00", Instant.now().plus(15, ChronoUnit.MINUTES));
        confirm(p, "key-refund-00001", "sim_card_success").andExpect(status().isOk());

        String partial = "{\"amount\":400.00,\"reason\":\"CANCELLATION_POLICY\",\"idempotencyKey\":\"refund-a-%s\"}"
                .formatted(p.bookingId());
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/internal/v1/payments/{id}/refunds", p.paymentId()).with(service())
                            .contentType(MediaType.APPLICATION_JSON).content(partial))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"));
        }
        mvc.perform(post("/internal/v1/payments/{id}/refunds", p.paymentId()).with(service())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":700.00,\"reason\":\"CANCELLATION_POLICY\",\"idempotencyKey\":\"refund-b-0001\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("REFUND_EXCEEDS_CAPTURED"));
        mvc.perform(post("/internal/v1/payments/{id}/refunds", p.paymentId()).with(service())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":600.00,\"reason\":\"CANCELLATION_POLICY\",\"idempotencyKey\":\"refund-c-0001\"}"))
                .andExpect(status().isOk());

        JsonNode detail = json.readTree(mvc.perform(get("/api/v1/payments/{id}", p.paymentId()).with(customer(p.customerId())))
                .andReturn().getResponse().getContentAsString());
        assertThat(detail.get("payment").get("status").asText()).isEqualTo("REFUNDED");
        assertThat(detail.get("payment").get("refundedAmount").decimalValue()).isEqualByComparingTo("1000.00");
        assertThat(detail.get("refunds")).hasSize(2);
        assertThat(detail.get("events").size()).isGreaterThanOrEqualTo(6);
    }

    @Test
    void paymentResponseAndEventHonourPublishedContracts() throws Exception {
        Created p = createPayment();
        String actual = mvc.perform(get("/internal/v1/payments/by-booking/{id}", p.bookingId()).with(service()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertHonoursContract(json.readTree(java.nio.file.Files.readString(
                java.nio.file.Path.of("../../contracts/payment-service/payment-response.json"))), json.readTree(actual), "");

        confirm(p, "key-contract-0001", "sim_card_success").andExpect(status().isOk());
        JsonNode event = json.readTree(events(p.paymentId()).get(0).getPayload());
        assertHonoursContract(json.readTree(java.nio.file.Files.readString(
                java.nio.file.Path.of("../../contracts/payment-service/payment-event.json"))), event, "");
    }

    /** Provider side of /contracts: every field of the published example must be present in the real response. */
    private static void assertHonoursContract(JsonNode example, JsonNode actual, String path) {
        example.fieldNames().forEachRemaining(field -> {
            assertThat(actual.has(field)).as("contract field %s%s", path, field).isTrue();
            JsonNode e = example.get(field);
            JsonNode a = actual.get(field);
            if (e.isObject() && a.isObject()) {
                assertHonoursContract(e, a, path + field + ".");
            } else if (e.isArray() && e.size() > 0 && a.isArray() && a.size() > 0) {
                assertHonoursContract(e.get(0), a.get(0), path + field + "[0].");
            }
        });
    }

    @Test
    void adminSearchWorksWithAndWithoutEachOptionalFilter() throws Exception {
        Created p = createPayment();
        mvc.perform(get("/api/v1/admin/payments").with(personnel("STAFF"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/payments").param("status", "CREATED").with(personnel("STAFF")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/payments").param("bookingId", p.bookingId().toString()).with(personnel("STAFF")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/payments/my").with(customer(p.customerId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    }

    private byte[] webhook(String eventId, String type, String reference, UUID paymentId) {
        return """
                {"id":"%s","type":"%s","created":%d,"data":{"reference":"%s","status":"%s","metadata":{"paymentId":"%s"}}}
                """.formatted(eventId, type, Instant.now().getEpochSecond(), reference,
                type.endsWith("succeeded") ? "SUCCEEDED" : "FAILED", paymentId).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void lateSuccessForAVoidedPaymentCannotConfirmIt() throws Exception {
        Created p = createPayment();
        mvc.perform(post("/internal/v1/payments/{id}/cancel", p.paymentId()).with(service())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"BOOKING_EXPIRED\"}")).andExpect(status().isOk());

        // customer tries to pay after expiry
        confirm(p, "key-late-confirm-1", "sim_card_success").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_ALREADY_PROCESSED"));
        // a (forged-looking but correctly signed) late provider success for the voided payment is ignored
        byte[] body = webhook("evt_late_" + UUID.randomUUID(), "charge.succeeded", "sim_ch_unknown", p.paymentId());
        mvc.perform(post("/api/v1/payments/webhooks/simulated").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header(WebhookSignature.HEADER, WebhookSignature.sign(secret.secret(), Instant.now().getEpochSecond(), body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("IGNORED"));

        mvc.perform(get("/api/v1/payments/{id}", p.paymentId()).with(customer(p.customerId())))
                .andExpect(jsonPath("$.payment.status").value("CANCELLED"));
        assertThat(events(p.paymentId())).isEmpty();
    }

    @Test
    void concurrentDuplicateWebhooksAreAppliedOnce() throws Exception {
        Created p = createPayment();
        String providerRef = json.readTree(confirm(p, "key-dup-hook-001", "sim_card_async_success")
                .andExpect(jsonPath("$.status").value("PENDING")).andReturn().getResponse().getContentAsString())
                .get("providerReference").asText();
        byte[] body = webhook("evt_dup_" + UUID.randomUUID(), "charge.succeeded", providerRef, p.paymentId());
        String signature = WebhookSignature.sign(secret.secret(), Instant.now().getEpochSecond(), body);

        var pool = java.util.concurrent.Executors.newFixedThreadPool(5);
        var start = new java.util.concurrent.CountDownLatch(1);
        var results = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
        for (int i = 0; i < 5; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/v1/payments/webhooks/simulated").contentType(MediaType.APPLICATION_JSON)
                        .content(body).header(WebhookSignature.HEADER, signature)).andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        for (var r : results) {
            // 200 (processed / duplicate) or 409 when two deliveries race on the receipt row; the provider retries 409
            assertThat(r.get()).isIn(200, 409);
        }
        pool.shutdown();
        mvc.perform(get("/api/v1/payments/{id}", p.paymentId()).with(customer(p.customerId())))
                .andExpect(jsonPath("$.payment.status").value("SUCCEEDED"));
        assertThat(events(p.paymentId())).extracting(OutboxEvent::getEventType).containsExactly("PAYMENT_SUCCEEDED");
    }

    @Test
    void roleBoundariesAreEnforced() throws Exception {
        Created p = createPayment();
        mvc.perform(post("/internal/v1/payments/{id}/cancel", p.paymentId()).with(personnel("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"BOOKING_EXPIRED\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/admin/payments/{id}", p.paymentId()).with(personnel("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUCCEEDED\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/payments/summary").with(personnel("ADMIN"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/payments").with(customer(p.customerId()))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/payments/my")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/payments/test-methods")).andExpect(status().isOk());
    }
}
