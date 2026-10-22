package com.grandvista.platform.client;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Client-credentials used by a backend service to obtain a short-lived service token from the
 * auth-service. The secret is supplied through the environment and never logged.
 */
@ConfigurationProperties("gv.service-auth")
public record ServiceAuthProperties(String tokenUri, String clientId, String clientSecret,
        Duration connectTimeout, Duration readTimeout) {

    public ServiceAuthProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(5) : readTimeout;
    }

    @Override
    public String toString() {
        return "ServiceAuthProperties[tokenUri=" + tokenUri + ", clientId=" + clientId + ", clientSecret=***]";
    }
}
