package com.grandvista.auth.service;

import com.grandvista.auth.config.AuthProperties;
import com.grandvista.platform.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Verifies backend-service client credentials (HTTP Basic) in constant time. */
@Component
public class ServiceClientAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(ServiceClientAuthenticator.class);
    static final int MIN_SECRET_LENGTH = 32;

    private final AuthProperties properties;

    public ServiceClientAuthenticator(AuthProperties properties) {
        this.properties = properties;
        properties.serviceClients().stream()
                .filter(c -> c.clientSecret() == null || c.clientSecret().length() < MIN_SECRET_LENGTH)
                .forEach(c -> log.warn("Service client '{}' has no secret or one shorter than {} characters and is disabled",
                        c.clientId(), MIN_SECRET_LENGTH));
    }

    /** @return the authenticated client id */
    public String authenticate(String authorizationHeader) {
        Optional<String[]> credentials = parseBasic(authorizationHeader);
        if (credentials.isEmpty()) {
            throw unauthorized();
        }
        String clientId = credentials.get()[0];
        byte[] presented = credentials.get()[1].getBytes(StandardCharsets.UTF_8);
        return properties.serviceClients().stream()
                .filter(c -> c.clientId() != null && c.clientId().equals(clientId))
                .filter(c -> c.clientSecret() != null && c.clientSecret().length() >= MIN_SECRET_LENGTH)
                .filter(c -> MessageDigest.isEqual(c.clientSecret().getBytes(StandardCharsets.UTF_8), presented))
                .map(AuthProperties.ServiceClient::clientId)
                .findFirst()
                .orElseThrow(ServiceClientAuthenticator::unauthorized);
    }

    private static Optional<String[]> parseBasic(String header) {
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return Optional.empty();
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            return colon <= 0 ? Optional.empty()
                    : Optional.of(new String[] {decoded.substring(0, colon), decoded.substring(colon + 1)});
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private static ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CLIENT", "Client authentication failed.");
    }
}
