package com.grandvista.booking.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.grandvista.booking.web.dto.BookingDtos.PaymentEventMessage;
import com.grandvista.platform.client.Retrier;
import com.grandvista.platform.client.ServiceCallException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Consumer side of the contracts in /contracts: the booking clients must understand the providers' messages. */
class DownstreamContractTest {

    private static final Path CONTRACTS = Path.of("../../contracts");
    private final Retrier noWaitRetrier = new Retrier(new Retrier.Policy(3, Duration.ZERO, 1, Duration.ZERO), d -> { });

    /** Mirrors the production builder: Spring Boot's Jackson settings (ISO-8601 dates, java.time support). */
    private static RestClient.Builder builder(String baseUrl) {
        ObjectMapper mapper = org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json()
                .featuresToDisable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        return RestClient.builder().baseUrl(baseUrl).messageConverters(converters -> {
            converters.removeIf(c -> c instanceof org.springframework.http.converter.json.MappingJackson2HttpMessageConverter);
            converters.add(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(mapper));
        });
    }

    private static String fixture(String name) throws Exception {
        return Files.readString(CONTRACTS.resolve(name));
    }

    @Test
    void roomHoldResponseIsUnderstood() throws Exception {
        RestClient.Builder builder = builder("http://room-service");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RoomServiceClient client = new RoomServiceClient(builder.build(), noWaitRetrier);
        UUID bookingId = UUID.fromString("8c2f8a9e-7b1d-4b9a-8d65-1a2b3c4d5e6f");

        server.expect(requestTo("http://room-service/internal/v1/holds")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.bookingId").value(bookingId.toString()))
                .andExpect(jsonPath("$.checkIn").value("2026-12-03"))
                .andRespond(withSuccess(fixture("room-service/hold-response.json"), MediaType.APPLICATION_JSON));

        var hold = client.hold(new RoomServiceClient.HoldRequest(bookingId, UUID.randomUUID(), LocalDate.parse("2026-12-03"),
                LocalDate.parse("2026-12-05"), 2));
        assertThat(hold.status()).isEqualTo("HELD");
        assertThat(hold.holdExpiresAt()).isNotNull();
        assertThat(hold.quote().total()).isEqualByComparingTo("19178.21");
        assertThat(hold.quote().subtotal().add(hold.quote().serviceFee()).add(hold.quote().taxes()))
                .isEqualByComparingTo(hold.quote().total());
        assertThat(hold.quote().nightlyRates()).hasSize(2);
        assertThat(hold.quote().pricingPolicyVersion()).isEqualTo(1);
    }

    @Test
    void paymentResponseIsUnderstood() throws Exception {
        RestClient.Builder builder = builder("http://payment-service");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PaymentServiceClient client = new PaymentServiceClient(builder.build(), noWaitRetrier);
        server.expect(requestTo("http://payment-service/internal/v1/payments"))
                .andExpect(jsonPath("$.amount").value(19178.21))
                .andRespond(withSuccess(fixture("payment-service/payment-response.json"), MediaType.APPLICATION_JSON));

        var payment = client.create(new PaymentServiceClient.CreatePayment(UUID.randomUUID(), "GV-7KQ2MX",
                UUID.randomUUID(), new BigDecimal("19178.21"), "INR", Instant.now()));
        assertThat(payment.status()).isEqualTo("CREATED");
        assertThat(payment.simulated()).isTrue();
    }

    @Test
    void paymentEventIsUnderstood() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        PaymentEventMessage event = mapper.readValue(fixture("payment-service/payment-event.json"), PaymentEventMessage.class);
        assertThat(event.eventType()).isEqualTo("PAYMENT_SUCCEEDED");
        assertThat(event.schemaVersion()).isEqualTo(1);
        assertThat(event.bookingId()).isNotNull();
    }

    @Test
    void transientFailuresAreRetriedThenReportedAndBusinessErrorsAreNot() {
        RestClient.Builder builder = builder("http://room-service");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RoomServiceClient client = new RoomServiceClient(builder.build(), noWaitRetrier);
        UUID id = UUID.randomUUID();

        server.expect(ExpectedCount.times(3), requestTo("http://room-service/internal/v1/holds/" + id + "/confirm"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> client.confirm(id)).isInstanceOf(ServiceCallException.class);
        server.verify();
        server.reset();

        server.expect(ExpectedCount.once(), requestTo("http://room-service/internal/v1/holds/" + id + "/confirm"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"status\":409,\"code\":\"INVENTORY_NO_LONGER_AVAILABLE\",\"detail\":\"gone\"}"));
        assertThatThrownBy(() -> client.confirm(id)).isInstanceOf(DownstreamException.class)
                .extracting("problem.code").isEqualTo("INVENTORY_NO_LONGER_AVAILABLE");
        server.verify();
    }

    @Test
    void roomServiceThatDoesNotAnswerInTimeFailsFastWithBoundedRetries() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        com.sun.net.httpserver.HttpServer slow = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        slow.createContext("/", exchange -> {
            calls.incrementAndGet();
            try {
                Thread.sleep(3_000); // longer than the client's read timeout
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        slow.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        slow.start();
        try {
            RestClient http = RestClient.builder()
                    .baseUrl("http://127.0.0.1:" + slow.getAddress().getPort())
                    .requestFactory(com.grandvista.platform.client.HttpClients.requestFactory(Duration.ofMillis(500), Duration.ofMillis(400)))
                    .build();
            RoomServiceClient client = new RoomServiceClient(http, noWaitRetrier);
            long started = System.nanoTime();
            assertThatThrownBy(() -> client.confirm(UUID.randomUUID())).isInstanceOf(ServiceCallException.class);
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            assertThat(calls.get()).isEqualTo(3);            // bounded: 3 attempts, then give up
            assertThat(elapsedMs).isLessThan(2_900);          // never waits for the slow server to finish
        } finally {
            slow.stop(0);
        }
    }
}
