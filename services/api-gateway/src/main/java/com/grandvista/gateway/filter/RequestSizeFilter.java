package com.grandvista.gateway.filter;

import com.grandvista.gateway.error.ProblemResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Rejects oversized requests at the edge (6 MB covers the largest legitimate upload: a 5 MB image). */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class RequestSizeFilter implements WebFilter {

    private final long maxBytes;
    private final ProblemResponses problems;

    public RequestSizeFilter(@Value("${gv.gateway.max-request-size:6MB}") DataSize maxSize, ProblemResponses problems) {
        this.maxBytes = maxSize.toBytes();
        this.problems = problems;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        long length = exchange.getRequest().getHeaders().getContentLength();
        if (length > maxBytes) {
            return problems.write(exchange, HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                    "The request body exceeds the allowed size.");
        }
        return chain.filter(exchange);
    }
}
