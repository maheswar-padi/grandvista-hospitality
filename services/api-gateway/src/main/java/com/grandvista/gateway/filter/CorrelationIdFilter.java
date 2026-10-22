package com.grandvista.gateway.filter;

import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Assigns every request a correlation ID (or keeps a well-formed one supplied by the client), forwards it
 * downstream, returns it to the client and writes one access-log line per request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter implements WebFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String ATTRIBUTE = "gv.correlationId";
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");
    private static final Logger access = LoggerFactory.getLogger("gateway.access");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
        String correlationId = incoming != null && VALID.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
        ServerHttpRequest request = exchange.getRequest().mutate().headers(h -> h.set(HEADER, correlationId)).build();
        ServerWebExchange mutated = exchange.mutate().request(request).build();
        mutated.getAttributes().put(ATTRIBUTE, correlationId);
        mutated.getResponse().getHeaders().set(HEADER, correlationId);
        long start = System.nanoTime();
        return chain.filter(mutated).doFinally(signal -> access.info("{} {} -> {} in {} ms [cid={}]",
                request.getMethod(), request.getPath().value(),
                mutated.getResponse().getStatusCode() == null ? "-" : mutated.getResponse().getStatusCode().value(),
                (System.nanoTime() - start) / 1_000_000, correlationId));
    }
}
