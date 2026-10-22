package com.grandvista.gateway.error;

import io.netty.channel.ConnectTimeoutException;
import java.net.ConnectException;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Consistent problem responses for routing failures; internals and stack traces are never exposed. */
@Component
@Order(-2)
public class GatewayErrorHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    private final ProblemResponses problems;

    public GatewayErrorHandler(ProblemResponses problems) {
        this.problems = problems;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        String path = exchange.getRequest().getPath().value();
        if (ex instanceof ResponseStatusException rse) {
            HttpStatus status = HttpStatus.resolve(rse.getStatusCode().value());
            if (status == HttpStatus.SERVICE_UNAVAILABLE) {
                log.warn("No healthy instance for {}: {}", path, rse.getReason());
                return problems.write(exchange, status, "SERVICE_UNAVAILABLE",
                        "The service is temporarily unavailable. Please retry shortly.");
            }
            if (status == HttpStatus.GATEWAY_TIMEOUT) {
                log.warn("Upstream timeout for {}", path);
                return problems.write(exchange, status, "UPSTREAM_TIMEOUT", "The service took too long to respond.");
            }
            if (status == HttpStatus.NOT_FOUND) {
                return problems.write(exchange, status, "NOT_FOUND", "No API route matches this request.");
            }
            if (status == HttpStatus.PAYLOAD_TOO_LARGE) {
                return problems.write(exchange, status, "PAYLOAD_TOO_LARGE", "The request body is too large.");
            }
            if (status != null && status.is4xxClientError()) {
                return problems.write(exchange, status, status.name(), status.getReasonPhrase());
            }
        }
        if (ex instanceof ConnectException || ex instanceof ConnectTimeoutException || ex.getCause() instanceof ConnectException) {
            log.warn("Upstream connection failed for {}: {}", path, ex.getMessage());
            return problems.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                    "The service is temporarily unavailable. Please retry shortly.");
        }
        if (ex instanceof TimeoutException) {
            return problems.write(exchange, HttpStatus.GATEWAY_TIMEOUT, "UPSTREAM_TIMEOUT",
                    "The service took too long to respond.");
        }
        log.error("Gateway error on {}", path, ex);
        return problems.write(exchange, HttpStatus.BAD_GATEWAY, "BAD_GATEWAY", "The request could not be completed.");
    }
}
