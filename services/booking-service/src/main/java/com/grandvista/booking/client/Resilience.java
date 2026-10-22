package com.grandvista.booking.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import java.util.function.Supplier;

/**
 * Resilience4j policies for calls to another service, one circuit breaker and one retry per service name
 * (configured under {@code resilience4j.*} in application.yml):
 * <ul>
 *   <li><b>Retry</b> — up to 3 attempts with exponential backoff and jitter, only for transient failures
 *       ({@link TransientFailure}: connection problems, timeouts, 502/503/504). Business errors (4xx) are never
 *       retried. All internal write endpoints are idempotent, so a retry cannot duplicate an effect.</li>
 *   <li><b>Circuit breaker</b> — opens when at least half of the recent calls failed, then fails fast
 *       ({@code CallNotPermittedException}) for 10 s before letting trial calls through.</li>
 *   <li><b>Timeouts</b> — enforced by the HTTP client (connect 2 s, read 5–8 s; see {@code gv.booking.*}).</li>
 * </ul>
 * The retry wraps the circuit breaker, so every attempt is counted and an open circuit is not retried.
 */
public class Resilience {

    private final CircuitBreakerRegistry circuitBreakers;
    private final RetryRegistry retries;

    public Resilience(CircuitBreakerRegistry circuitBreakers, RetryRegistry retries) {
        this.circuitBreakers = circuitBreakers;
        this.retries = retries;
    }

    public <T> T call(String service, Supplier<T> action) {
        CircuitBreaker circuitBreaker = circuitBreakers.circuitBreaker(service);
        Retry retry = retries.retry(service);
        return Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(circuitBreaker, action)).get();
    }

    public CircuitBreaker.State state(String service) {
        return circuitBreakers.circuitBreaker(service).getState();
    }
}
