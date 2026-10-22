package com.grandvista.platform.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

class RetrierTest {

    private final List<Duration> sleeps = new ArrayList<>();
    private final Retrier retrier = new Retrier(
            new Retrier.Policy(3, Duration.ofMillis(100), 2.0, Duration.ofMillis(150)), sleeps::add);

    @Test
    void retriesTransientFailuresThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        String result = retrier.call("op", () -> {
            if (calls.incrementAndGet() < 3) {
                throw new ResourceAccessException("connection refused");
            }
            return "ok";
        }, Retrier.TRANSIENT_HTTP_FAILURE);

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(3);
        assertThat(sleeps).hasSize(2);
        // backoff is capped by maxBackoff (150ms) plus at most 20% jitter
        assertThat(sleeps).allSatisfy(d -> assertThat(d.toMillis()).isBetween(80L, 180L));
    }

    @Test
    void stopsAfterMaxAttempts() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> retrier.call("op", () -> {
            calls.incrementAndGet();
            throw HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "down", HttpHeaders.EMPTY, null, null);
        }, Retrier.TRANSIENT_HTTP_FAILURE)).isInstanceOf(HttpServerErrorException.class);
        assertThat(calls).hasValue(3);
    }

    @Test
    void neverRetriesClientErrors() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> retrier.call("op", () -> {
            calls.incrementAndGet();
            throw HttpClientErrorException.create(HttpStatus.CONFLICT, "conflict", HttpHeaders.EMPTY, null, null);
        }, Retrier.TRANSIENT_HTTP_FAILURE)).isInstanceOf(HttpClientErrorException.class);
        assertThat(calls).hasValue(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void internalServerErrorIsNotTreatedAsTransient() {
        var ex = HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "boom", HttpHeaders.EMPTY, null, null);
        assertThat(Retrier.TRANSIENT_HTTP_FAILURE.test(ex)).isFalse();
    }
}
