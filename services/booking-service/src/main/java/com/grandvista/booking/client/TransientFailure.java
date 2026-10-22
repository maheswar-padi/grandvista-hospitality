package com.grandvista.booking.client;

import java.util.function.Predicate;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Failures worth retrying: transport errors and timeouts ({@link ResourceAccessException}), 502/503/504 answers,
 * and "no instance available" from the load balancer. Client errors (4xx) never are.
 * Referenced by name from {@code resilience4j.retry.configs.default.retry-exception-predicate}.
 */
public class TransientFailure implements Predicate<Throwable> {

    @Override
    public boolean test(Throwable ex) {
        if (ex instanceof ResourceAccessException) {
            return true;
        }
        if (ex instanceof HttpServerErrorException server) {
            return server.getStatusCode().isSameCodeAs(HttpStatus.BAD_GATEWAY)
                    || server.getStatusCode().isSameCodeAs(HttpStatus.SERVICE_UNAVAILABLE)
                    || server.getStatusCode().isSameCodeAs(HttpStatus.GATEWAY_TIMEOUT);
        }
        return ex instanceof IllegalStateException && String.valueOf(ex.getMessage()).contains("No instances available");
    }
}
