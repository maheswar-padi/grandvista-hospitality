package com.grandvista.platform.client;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Bounded retry with exponential backoff and jitter. Use it only for operations that are idempotent
 * on the callee side (every internal command in this platform carries an idempotency key).
 */
public class Retrier {

    private static final Logger log = LoggerFactory.getLogger(Retrier.class);

    public record Policy(int maxAttempts, Duration initialBackoff, double multiplier, Duration maxBackoff) {
        public static Policy defaults() {
            return new Policy(3, Duration.ofMillis(200), 2.0, Duration.ofSeconds(2));
        }

        public static Policy none() {
            return new Policy(1, Duration.ZERO, 1.0, Duration.ZERO);
        }
    }

    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /** Transport failures and 502/503/504 are transient; client errors (4xx) never are. */
    public static final Predicate<Throwable> TRANSIENT_HTTP_FAILURE = ex ->
            ex instanceof ResourceAccessException
                    || ex instanceof HttpServerErrorException server
                    && (server.getStatusCode().isSameCodeAs(HttpStatus.BAD_GATEWAY)
                    || server.getStatusCode().isSameCodeAs(HttpStatus.SERVICE_UNAVAILABLE)
                    || server.getStatusCode().isSameCodeAs(HttpStatus.GATEWAY_TIMEOUT));

    private final Policy policy;
    private final Sleeper sleeper;

    public Retrier(Policy policy) {
        this(policy, d -> Thread.sleep(d.toMillis()));
    }

    public Retrier(Policy policy, Sleeper sleeper) {
        if (policy.maxAttempts() < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        this.policy = policy;
        this.sleeper = sleeper;
    }

    public <T> T call(String operation, Supplier<T> action, Predicate<Throwable> retryable) {
        Duration backoff = policy.initialBackoff();
        for (int attempt = 1; ; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException ex) {
                if (attempt >= policy.maxAttempts() || !retryable.test(ex)) {
                    throw ex;
                }
                Duration wait = withJitter(backoff);
                log.warn("{} failed on attempt {}/{} ({}); retrying in {} ms", operation, attempt,
                        policy.maxAttempts(), ex.getClass().getSimpleName(), wait.toMillis());
                try {
                    sleeper.sleep(wait);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
                backoff = min(Duration.ofMillis((long) (backoff.toMillis() * policy.multiplier())), policy.maxBackoff());
            }
        }
    }

    private static Duration withJitter(Duration base) {
        if (base.isZero()) {
            return base;
        }
        long millis = base.toMillis();
        long jitter = ThreadLocalRandom.current().nextLong(-(millis / 5), millis / 5 + 1);
        return Duration.ofMillis(Math.max(0, millis + jitter));
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
