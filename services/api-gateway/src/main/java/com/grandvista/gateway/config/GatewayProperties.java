package com.grandvista.gateway.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gv.gateway")
public record GatewayProperties(
        String jwtIssuer,
        String jwkSetUri,
        List<String> corsAllowedOrigins,
        boolean trustForwardedFor,
        RateLimit authRateLimit,
        RateLimit apiRateLimit) {

    public GatewayProperties {
        jwtIssuer = jwtIssuer == null ? "grandvista-auth" : jwtIssuer;
        corsAllowedOrigins = corsAllowedOrigins == null ? List.of() : corsAllowedOrigins;
        authRateLimit = authRateLimit == null ? new RateLimit(10, Duration.ofMinutes(1)) : authRateLimit;
        apiRateLimit = apiRateLimit == null ? new RateLimit(300, Duration.ofMinutes(1)) : apiRateLimit;
    }

    /** Token bucket: {@code capacity} requests, refilled evenly over {@code period}. */
    public record RateLimit(int capacity, Duration period) {
    }
}
