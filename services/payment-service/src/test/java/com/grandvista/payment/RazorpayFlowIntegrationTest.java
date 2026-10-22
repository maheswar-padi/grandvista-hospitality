package com.grandvista.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grandvista.payment.provider.razorpay.RazorpaySignatures;
import com.grandvista.payment.service.PaymentReconciliationJob;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The Razorpay gateway against a fake Razorpay API (a local HTTP server that follows Razorpay's documented
 * request/response shapes): order creation, signed checkout confirmation, server-side verification, capture,
 * signed webhooks, reconciliation, refunds and refunds of captures that arrive for voided payments.
 * Real Razorpay test-mode calls need keys and are not part of the automated suite.
 */
@SpringBootTest(properties = {"eureka.client.enabled=false", "gv.security.jwt.jwk-set-uri=http://localhost:1/unused",
        "gv.payment.provider=razorpay", "gv.payment.outbox.relay-interval=1h", "gv.payment.reconciliation.interval=1h",
        "gv.payment.razorpay.key-id=rzp_test_GVFAKE00001", "gv.payment.razorpay.key-secret=fake_key_secret_for_tests",
        "gv.payment.razorpay.webhook-secret=fake_webhook_secret_for_tests",
        "gv.payment.webhook-secret=test-webhook-secret-0123456789abcdef"})
@AutoConfigureMockMvc
@Import(RazorpayFlowIntegrationTest.Containers.class)
@Testcontainers(disabledWithoutDocker = true)
class RazorpayFlowIntegrationTest {

    private static final String KEY_SECRET = "fake_key_secret_for_tests";
    private static final String WEBHOOK_SECRET = "fake_webhook_secret_for_tests";

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {
        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>("postgres:16-alpine");
        }
    }

    /** In-memory stand-in for api.razorpay.com. */
    static final class FakeRazorpay {
        final HttpServer server;
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<JsonNode> orderBodies = new CopyOnWriteArrayList<>();
        final List<JsonNode> refundBodies = new CopyOnWriteArrayList<>();
        final Map<String, ObjectNodeHolder> payments = new ConcurrentHashMap<>();
        final AtomicInteger orderSeq = new AtomicInteger();
        final ObjectMapper mapper = new ObjectMapper();
        volatile String lastAuthorization;

        record ObjectNodeHolder(String orderId, long amount, String status) {
        }

        FakeRazorpay() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/", this::handle);
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        String paymentJson(String id, ObjectNodeHolder p) {
            return """
                    {"id":"%s","entity":"payment","amount":%d,"currency":"INR","status":"%s","order_id":"%s",
                     "method":"card","captured":%s,"error_code":null,"error_description":null}
                    """.formatted(id, p.amount(), p.status(), p.orderId(), p.status().equals("captured"));
        }

        void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            lastAuthorization = ex.getRequestHeaders().getFirst("Authorization");
            byte[] in = ex.getRequestBody().readAllBytes();
            calls.add(method + " " + path);
            String body;
            int status = 200;
            if (method.equals("POST") && path.equals("/v1/orders")) {
                JsonNode req = mapper.readTree(in);
                orderBodies.add(req);
                body = """
                        {"id":"order_GVTEST%04d","entity":"order","amount":%d,"amount_paid":0,"currency":"%s",
                         "receipt":"%s","status":"created"}
                        """.formatted(orderSeq.incrementAndGet(), req.get("amount").asLong(), req.get("currency").asText(),
                        req.get("receipt").asText());
            } else if (method.equals("GET") && path.matches("/v1/payments/[^/]+")) {
                String id = path.substring("/v1/payments/".length());
                ObjectNodeHolder p = payments.get(id);
                if (p == null) {
                    status = 400;
                    body = "{\"error\":{\"code\":\"BAD_REQUEST_ERROR\",\"description\":\"The id provided does not exist\"}}";
                } else {
                    body = paymentJson(id, p);
                }
            } else if (method.equals("POST") && path.matches("/v1/payments/[^/]+/capture")) {
                String id = path.split("/")[3];
                ObjectNodeHolder p = payments.get(id);
                if (!p.status().equals("authorized")) {
                    status = 400;
                    body = "{\"error\":{\"code\":\"BAD_REQUEST_ERROR\",\"description\":\"This payment has already been captured\"}}";
                } else {
                    p = new ObjectNodeHolder(p.orderId(), p.amount(), "captured");
                    payments.put(id, p);
                    body = paymentJson(id, p);
                }
            } else if (method.equals("GET") && path.matches("/v1/orders/[^/]+/payments")) {
                String orderId = path.split("/")[3];
                StringBuilder items = new StringBuilder();
                payments.forEach((id, p) -> {
                    if (p.orderId().equals(orderId)) {
                        items.append(items.isEmpty() ? "" : ",").append(paymentJson(id, p));
                    }
                });
                body = "{\"entity\":\"collection\",\"count\":1,\"items\":[" + items + "]}";
            } else if (method.equals("POST") && path.matches("/v1/payments/[^/]+/refund")) {
                JsonNode req = mapper.readTree(in);
                refundBodies.add(req);
                body = "{\"id\":\"rfnd_GVTEST%04d\",\"entity\":\"refund\",\"amount\":%d,\"payment_id\":\"%s\",\"status\":\"processed\"}"
                        .formatted(refundBodies.size(), req.get("amount").asLong(), path.split("/")[3]);
            } else {
                status = 404;
                body = "{\"error\":{\"code\":\"NOT_FOUND\",\"description\":\"no such endpoint\"}}";
            }
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        }
    }

    static final FakeRazorpay RAZORPAY;

    static {
        try {
            RAZORPAY = new FakeRazorpay();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void razorpay(DynamicPropertyRegistry registry) {
        registry.add("gv.payment.razorpay.base-url", RAZORPAY::url);
    }

    @AfterAll
    static void stop() {
        RAZORPAY.server.stop(0);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PaymentReconciliationJob reconciliation;

    @BeforeEach
    void reset() {
        RAZORPAY.calls.clear();
        RAZORPAY.orderBodies.clear();
        RAZORPAY.refundBodies.clear();
    }

    private static JwtRequestPostProcessor service() {
        return jwt().jwt(j -> j.subject("service:booking-service").claim("roles", List.of("SERVICE")))
                .authorities(new SimpleGrantedAuthority("ROLE_SERVICE"));
    }

    private static JwtRequestPostProcessor customer(UUID id) {
        return jwt().jwt(j -> j.subject(id.toString()).claim("roles", List.of("CUSTOMER")).claim("email", "asha@example.test"))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private record Created(UUID paymentId, UUID bookingId, UUID customerId) {
    }

    private Created createPayment() throws Exception {
        UUID booking = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        String body = """
                {"bookingId":"%s","bookingReference":"GV-RZP%s","customerId":"%s","amount":10584.00,"currency":"INR","expiresAt":"%s"}
                """.formatted(booking, booking.toString().substring(0, 3).toUpperCase(), customer,
                Instant.now().plus(15, ChronoUnit.MINUTES));
        String response = mvc.perform(post("/internal/v1/payments").with(service()).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.provider").value("RAZORPAY"))
                .andExpect(jsonPath("$.simulated").value(false))
                .andReturn().getResponse().getContentAsString();
        return new Created(UUID.fromString(json.readTree(response).get("id").asText()), booking, customer);
    }

    private JsonNode checkout(Created p) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/payments/{id}/checkout", p.paymentId()).with(customer(p.customerId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private ResultActions confirm(Created p, String key, String paymentId, String orderId, String signature) throws Exception {
        return mvc.perform(post("/api/v1/payments/{id}/confirm", p.paymentId()).with(customer(p.customerId()))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"razorpayPaymentId":"%s","razorpayOrderId":"%s","razorpaySignature":"%s"}
                        """.formatted(paymentId, orderId, signature)));
    }

    private ResultActions webhook(String event, String eventId, String paymentId, String orderId, long amount, String signature)
            throws Exception {
        String body = """
                {"entity":"event","account_id":"acc_GVTEST","event":"%s","contains":["payment"],
                 "payload":{"payment":{"entity":{"id":"%s","entity":"payment","amount":%d,"currency":"INR",
                 "status":"%s","order_id":"%s"}}},"created_at":%d}
                """.formatted(event, paymentId, amount, event.equals("payment.failed") ? "failed" : "captured", orderId,
                Instant.now().getEpochSecond());
        String sig = signature != null ? signature : RazorpaySignatures.hmacHex(WEBHOOK_SECRET, body);
        return mvc.perform(post("/api/v1/payments/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON)
                .header("X-Razorpay-Signature", sig).header("X-Razorpay-Event-Id", eventId).content(body));
    }

    private String paymentStatus(UUID id) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, id);
    }

    private long outboxEvents(UUID id, String type) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = ?", Long.class, id, type);
    }

    @Test
    void checkoutCreatesOneOrderForTheExactAmountInPaise() throws Exception {
        Created p = createPayment();
        JsonNode first = checkout(p);
        JsonNode second = checkout(p);

        assertThat(first.get("provider").asText()).isEqualTo("RAZORPAY");
        assertThat(first.get("keyId").asText()).isEqualTo("rzp_test_GVFAKE00001");
        assertThat(first.get("amountMinorUnits").asLong()).isEqualTo(1_058_400L);
        assertThat(first.get("prefillEmail").asText()).isEqualTo("asha@example.test");
        assertThat(first.toString()).doesNotContain(KEY_SECRET).doesNotContain(WEBHOOK_SECRET);
        assertThat(second.get("orderId").asText()).isEqualTo(first.get("orderId").asText());
        assertThat(RAZORPAY.orderBodies).hasSize(1);
        assertThat(RAZORPAY.orderBodies.get(0).get("amount").asLong()).isEqualTo(1_058_400L);
        assertThat(RAZORPAY.orderBodies.get(0).get("receipt").asText()).isEqualTo(p.paymentId().toString());
        assertThat(RAZORPAY.lastAuthorization).isEqualTo("Basic " + java.util.Base64.getEncoder()
                .encodeToString(("rzp_test_GVFAKE00001:" + KEY_SECRET).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void verifiedCheckoutIsConfirmedWithRazorpayCapturedOnceAndPublished() throws Exception {
        Created p = createPayment();
        String orderId = checkout(p).get("orderId").asText();
        RAZORPAY.payments.put("pay_GVOK0000001", new FakeRazorpay.ObjectNodeHolder(orderId, 1_058_400L, "authorized"));
        String signature = RazorpaySignatures.hmacHex(KEY_SECRET, orderId + "|pay_GVOK0000001");

        confirm(p, "rzp-confirm-0001", "pay_GVOK0000001", orderId, signature)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.providerReference").value("pay_GVOK0000001"));
        confirm(p, "rzp-confirm-0001", "pay_GVOK0000001", orderId, signature)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"));

        assertThat(RAZORPAY.calls.stream().filter(c -> c.endsWith("/capture")).count()).isEqualTo(1);
        assertThat(outboxEvents(p.paymentId(), "PAYMENT_SUCCEEDED")).isEqualTo(1);

        // a captured webhook for the same payment afterwards changes nothing
        webhook("payment.captured", "evt_GVLATE0001", "pay_GVOK0000001", orderId, 1_058_400L, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("IGNORED"));
        assertThat(outboxEvents(p.paymentId(), "PAYMENT_SUCCEEDED")).isEqualTo(1);
    }

    @Test
    void forgedBrowserSignatureNeverConfirmsAndReconciliationAsksRazorpay() throws Exception {
        Created p = createPayment();
        String orderId = checkout(p).get("orderId").asText();
        RAZORPAY.payments.put("pay_GVFORGE0001", new FakeRazorpay.ObjectNodeHolder(orderId, 1_058_400L, "captured"));

        confirm(p, "rzp-confirm-0002", "pay_GVFORGE0001", orderId, "0".repeat(64))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
        assertThat(RAZORPAY.calls).noneMatch(c -> c.startsWith("GET /v1/payments/"));
        assertThat(outboxEvents(p.paymentId(), "PAYMENT_SUCCEEDED")).isZero();

        reconciliation.reconcile(p.paymentId());
        assertThat(paymentStatus(p.paymentId())).isEqualTo("SUCCEEDED");
        assertThat(RAZORPAY.calls).contains("GET /v1/orders/" + orderId + "/payments");
    }

    @Test
    void capturedWebhookConfirmsAPaymentWhoseBrowserClosedAndIsDeduplicated() throws Exception {
        Created p = createPayment();
        String orderId = checkout(p).get("orderId").asText();

        webhook("payment.captured", "evt_GVBAD00001", "pay_GVHOOK0001", orderId, 1_058_400L, "f".repeat(64))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));
        webhook("payment.captured", "evt_GVHOOK0001", "pay_GVHOOK0001", orderId, 1_058_400L, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("PROCESSED"));
        webhook("payment.captured", "evt_GVHOOK0001", "pay_GVHOOK0001", orderId, 1_058_400L, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("DUPLICATE"));

        assertThat(paymentStatus(p.paymentId())).isEqualTo("SUCCEEDED");
        assertThat(outboxEvents(p.paymentId(), "PAYMENT_SUCCEEDED")).isEqualTo(1);
    }

    @Test
    void wrongAmountInAWebhookIsNotApplied() throws Exception {
        Created p = createPayment();
        String orderId = checkout(p).get("orderId").asText();
        webhook("payment.captured", "evt_GVAMT00001", "pay_GVAMT00001", orderId, 100L, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("IGNORED"));
        assertThat(paymentStatus(p.paymentId())).isEqualTo("CREATED");
    }

    @Test
    void captureArrivingForAVoidedPaymentIsRefundedNotConfirmed() throws Exception {
        Created p = createPayment();
        String orderId = checkout(p).get("orderId").asText();
        mvc.perform(post("/internal/v1/payments/{id}/cancel", p.paymentId()).with(service())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"BOOKING_EXPIRED\"}")).andExpect(status().isOk());

        webhook("order.paid", "evt_GVORPH0001", "pay_GVORPH0001", orderId, 1_058_400L, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("PROCESSED"));

        assertThat(paymentStatus(p.paymentId())).isEqualTo("CANCELLED");
        assertThat(outboxEvents(p.paymentId(), "PAYMENT_SUCCEEDED")).isZero();
        assertThat(RAZORPAY.refundBodies).hasSize(1);
        assertThat(RAZORPAY.refundBodies.get(0).get("amount").asLong()).isEqualTo(1_058_400L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_events WHERE payment_id = ? AND event_type = 'ORPHAN_CAPTURE_REFUNDED'",
                Long.class, p.paymentId())).isEqualTo(1);
    }

    @Test
    void refundsAreSentToRazorpayInPaise() throws Exception {
        Created p = createPayment();
        String orderId = checkout(p).get("orderId").asText();
        RAZORPAY.payments.put("pay_GVREF00001", new FakeRazorpay.ObjectNodeHolder(orderId, 1_058_400L, "captured"));
        confirm(p, "rzp-confirm-0003", "pay_GVREF00001", orderId, RazorpaySignatures.hmacHex(KEY_SECRET, orderId + "|pay_GVREF00001"))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));

        mvc.perform(post("/internal/v1/payments/{id}/refunds", p.paymentId()).with(service()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":5292.00,\"reason\":\"CUSTOMER_CANCELLATION\",\"idempotencyKey\":\"refund-rzp-0001\"}"))
                .andExpect(status().is2xxSuccessful()).andExpect(jsonPath("$.providerReference").value("rfnd_GVTEST0001"));
        assertThat(RAZORPAY.refundBodies.get(0).get("amount").asLong()).isEqualTo(529_200L);
        assertThat(paymentStatus(p.paymentId())).isEqualTo("PARTIALLY_REFUNDED");
    }

    @Test
    void simulatedPathsAreClosedInRazorpayMode() throws Exception {
        Created p = createPayment();
        mvc.perform(post("/api/v1/payments/{id}/confirm", p.paymentId()).with(customer(p.customerId()))
                        .header("Idempotency-Key", "rzp-token-0001").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentMethodToken\":\"sim_card_success\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CHECKOUT_RESULT_REQUIRED"));
        assertThat(paymentStatus(p.paymentId())).isEqualTo("CREATED");
        mvc.perform(post("/api/v1/payments/webhooks/simulated").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        confirm(p, "rzp-nocheckout-1", "pay_GVNONE0001", "order_GVNONE0001", "a".repeat(64))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHECKOUT_NOT_STARTED"));
    }
}
