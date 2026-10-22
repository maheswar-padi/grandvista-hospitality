package com.grandvista.gateway.web;

import com.grandvista.gateway.error.ProblemResponses;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Fallback of the per-service circuit breakers: a fast, uniform problem response instead of a hanging request
 * when a service is unreachable, timing out, or its circuit is open. Clients are told when to retry.
 */
@RestController
public class FallbackController {

    private static final Logger log = LoggerFactory.getLogger(FallbackController.class);

    private final ProblemResponses problems;

    public FallbackController(ProblemResponses problems) {
        this.problems = problems;
    }

    @RequestMapping("/fallback/{service}")
    public Mono<Void> fallback(@PathVariable String service, ServerWebExchange exchange) {
        Throwable cause = exchange.getAttribute(ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR);
        log.warn("Fallback for {} {}: {}", service, exchange.getRequest().getPath(), cause == null ? "unknown" : cause.toString());
        if (cause instanceof TimeoutException) {
            return problems.write(exchange, HttpStatus.GATEWAY_TIMEOUT, "UPSTREAM_TIMEOUT",
                    "The service took too long to respond.", Map.of("service", service));
        }
        exchange.getResponse().getHeaders().set("Retry-After", "10");
        String detail = cause instanceof CallNotPermittedException
                ? "The service is temporarily unavailable (circuit open). Please retry shortly."
                : "The service is temporarily unavailable. Please retry shortly.";
        return problems.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", detail,
                Map.of("service", service));
    }
}
