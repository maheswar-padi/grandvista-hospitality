package com.grandvista.gateway.filter;

import com.grandvista.gateway.config.GatewayProperties;
import com.grandvista.gateway.error.ProblemResponses;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Per-client token-bucket rate limiting, applied before authentication. Credential endpoints (login,
 * register, refresh) get a strict budget to slow brute-force attempts; the rest of the API a generous one.
 *
 * <p>State is in memory, which is correct for a single gateway instance. With several instances use the
 * Redis-backed {@code RequestRateLimiter} filter instead (documented limitation).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter implements WebFilter {

    private static final int MAX_TRACKED_CLIENTS = 50_000;

    private final GatewayProperties properties;
    private final ProblemResponses problems;
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong lastCleanup = new AtomicLong(System.nanoTime());

    public RateLimitFilter(GatewayProperties properties, ProblemResponses problems) {
        this.properties = properties;
        this.problems = problems;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var request = exchange.getRequest();
        String path = request.getPath().value();
        if (!path.startsWith("/api/") || request.getMethod() == HttpMethod.OPTIONS) {
            return chain.filter(exchange);
        }
        boolean credentialEndpoint = request.getMethod() == HttpMethod.POST && (path.equals("/api/v1/auth/login")
                || path.equals("/api/v1/auth/register") || path.equals("/api/v1/auth/refresh"));
        GatewayProperties.RateLimit limit = credentialEndpoint ? properties.authRateLimit() : properties.apiRateLimit();
        String key = (credentialEndpoint ? "auth:" : "api:") + ClientAddress.of(request, properties.trustForwardedFor());
        cleanupIfNeeded();
        TokenBucket bucket = buckets.computeIfAbsent(key, k -> new TokenBucket(limit.capacity(), limit.period()));
        long retryAfterSeconds = bucket.tryConsume();
        if (retryAfterSeconds > 0) {
            exchange.getResponse().getHeaders().set("Retry-After", Long.toString(retryAfterSeconds));
            return problems.write(exchange, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Too many requests. Please wait before trying again.", Map.of("retryAfterSeconds", retryAfterSeconds));
        }
        return chain.filter(exchange);
    }

    private void cleanupIfNeeded() {
        long now = System.nanoTime();
        long last = lastCleanup.get();
        if ((buckets.size() > MAX_TRACKED_CLIENTS || now - last > 300_000_000_000L) && lastCleanup.compareAndSet(last, now)) {
            buckets.entrySet().removeIf(e -> e.getValue().isIdle(now));
        }
    }

    /** Classic token bucket: refills continuously at capacity/period. */
    static final class TokenBucket {
        private final double capacity;
        private final double refillPerNano;
        private double tokens;
        private long lastRefill;

        TokenBucket(int capacity, java.time.Duration period) {
            this.capacity = capacity;
            this.refillPerNano = capacity / (double) period.toNanos();
            this.tokens = capacity;
            this.lastRefill = System.nanoTime();
        }

        /** @return 0 when allowed, otherwise the suggested wait in whole seconds */
        synchronized long tryConsume() {
            long now = System.nanoTime();
            tokens = Math.min(capacity, tokens + (now - lastRefill) * refillPerNano);
            lastRefill = now;
            if (tokens >= 1) {
                tokens -= 1;
                return 0;
            }
            return Math.max(1, (long) Math.ceil((1 - tokens) / refillPerNano / 1_000_000_000d));
        }

        synchronized boolean isIdle(long now) {
            return tokens + (now - lastRefill) * refillPerNano >= capacity;
        }
    }
}
