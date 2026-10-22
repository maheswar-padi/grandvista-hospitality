package com.grandvista.gateway.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ConnectTimeoutException;
import java.net.ConnectException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;

/** Every failure mode becomes a problem+json answer with a stable code — never a stack trace. */
class GatewayErrorHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final GatewayErrorHandler handler = new GatewayErrorHandler(new ProblemResponses(mapper));

    static java.util.stream.Stream<Arguments> failures() {
        return java.util.stream.Stream.of(
                Arguments.of(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "no instance"), 503, "SERVICE_UNAVAILABLE"),
                Arguments.of(new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT), 504, "UPSTREAM_TIMEOUT"),
                Arguments.of(new ResponseStatusException(HttpStatus.NOT_FOUND), 404, "NOT_FOUND"),
                Arguments.of(new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE), 413, "PAYLOAD_TOO_LARGE"),
                Arguments.of(new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED), 405, "METHOD_NOT_ALLOWED"),
                Arguments.of(new ConnectException("Connection refused"), 503, "SERVICE_UNAVAILABLE"),
                Arguments.of(new ConnectTimeoutException("connect timed out"), 503, "SERVICE_UNAVAILABLE"),
                Arguments.of(new RuntimeException("wrapped", new ConnectException("refused")), 503, "SERVICE_UNAVAILABLE"),
                Arguments.of(new TimeoutException("slow"), 504, "UPSTREAM_TIMEOUT"),
                Arguments.of(new IllegalStateException("secret internal detail at com.example.Foo"), 502, "BAD_GATEWAY"));
    }

    @ParameterizedTest
    @MethodSource("failures")
    void mapsFailuresToProblemJsonWithoutInternals(Throwable error, int status, String code) throws Exception {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/rooms").build());
        handler.handle(exchange, error).block();

        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(status);
        assertThat(exchange.getResponse().getHeaders().getContentType().toString()).isEqualTo("application/problem+json");
        String body = exchange.getResponse().getBodyAsString().block();
        JsonNode problem = mapper.readTree(body);
        assertThat(problem.get("code").asText()).isEqualTo(code);
        assertThat(problem.get("status").asInt()).isEqualTo(status);
        assertThat(problem.get("instance").asText()).isEqualTo("/api/v1/rooms");
        assertThat(body).doesNotContain("secret internal detail").doesNotContain("Exception").doesNotContain("\tat ");
    }
}
