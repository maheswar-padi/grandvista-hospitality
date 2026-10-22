package com.grandvista.platform.client;

import java.io.IOException;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/** Adds the service bearer token to internal calls; drops a cached token that a callee rejected. */
public class ServiceTokenInterceptor implements ClientHttpRequestInterceptor {

    private final ServiceTokenProvider tokenProvider;

    public ServiceTokenInterceptor(ServiceTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        request.getHeaders().setBearerAuth(tokenProvider.getToken());
        ClientHttpResponse response = execution.execute(request, body);
        if (response.getStatusCode().isSameCodeAs(HttpStatus.UNAUTHORIZED)) {
            tokenProvider.invalidate();
        }
        return response;
    }
}
