package com.grandvista.platform.client;

import com.grandvista.platform.web.CorrelationId;
import java.io.IOException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/** Forwards the current correlation ID on every outbound service call. */
public class CorrelationIdPropagationInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        if (!request.getHeaders().containsKey(CorrelationId.HEADER)) {
            request.getHeaders().set(CorrelationId.HEADER, CorrelationId.currentOrNew());
        }
        return execution.execute(request, body);
    }
}
