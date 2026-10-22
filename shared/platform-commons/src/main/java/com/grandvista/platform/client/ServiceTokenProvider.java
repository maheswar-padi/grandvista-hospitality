package com.grandvista.platform.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Obtains and caches a service access token using the OAuth 2.0 client-credentials grant against the
 * auth-service. Tokens are refreshed shortly before they expire; concurrent callers share one fetch.
 */
public class ServiceTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(ServiceTokenProvider.class);
    private static final Duration REFRESH_MARGIN = Duration.ofSeconds(60);

    private final RestClient restClient;
    private final ServiceAuthProperties properties;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile String token;
    private volatile Instant expiresAt = Instant.EPOCH;

    record TokenResponse(@JsonProperty("access_token") String accessToken,
                         @JsonProperty("expires_in") long expiresIn) {
    }

    public ServiceTokenProvider(RestClient.Builder builder, ServiceAuthProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.restClient = builder.clone()
                .requestFactory(HttpClients.requestFactory(properties.connectTimeout(), properties.readTimeout()))
                .requestInterceptor(new CorrelationIdPropagationInterceptor())
                .build();
    }

    public String getToken() {
        if (isFresh()) {
            return token;
        }
        lock.lock();
        try {
            if (!isFresh()) {
                fetch();
            }
            return token;
        } finally {
            lock.unlock();
        }
    }

    /** Drops the cached token, e.g. after a downstream 401 caused by an auth-service key rotation. */
    public void invalidate() {
        expiresAt = Instant.EPOCH;
    }

    private boolean isFresh() {
        return token != null && clock.instant().isBefore(expiresAt.minus(REFRESH_MARGIN));
    }

    private void fetch() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        TokenResponse response;
        try {
            response = restClient.post()
                    .uri(properties.tokenUri())
                    .headers(h -> h.setBasicAuth(properties.clientId(), properties.clientSecret()))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
        } catch (RestClientException ex) {
            throw new ServiceCallException("auth-service", "Could not obtain a service token", ex);
        }
        if (response == null || response.accessToken() == null) {
            throw new ServiceCallException("auth-service", "Empty service token response", null);
        }
        token = response.accessToken();
        expiresAt = clock.instant().plusSeconds(response.expiresIn());
        log.debug("Obtained service token for client {} valid for {}s", properties.clientId(), response.expiresIn());
    }
}
