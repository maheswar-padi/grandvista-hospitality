package com.grandvista.platform.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings used to validate access tokens issued by the auth-service.
 *
 * @param issuer      required {@code iss} claim
 * @param audiences   accepted {@code aud} values; a token must carry at least one of them
 * @param jwkSetUri   where the auth-service publishes its public signing keys (JWKS)
 */
@ConfigurationProperties("gv.security.jwt")
public record JwtProperties(String issuer, List<String> audiences, String jwkSetUri) {

    public static final String PUBLIC_AUDIENCE = "grandvista-api";
    public static final String INTERNAL_AUDIENCE = "grandvista-internal";

    public JwtProperties {
        issuer = issuer == null ? "grandvista-auth" : issuer;
        audiences = audiences == null || audiences.isEmpty() ? List.of(PUBLIC_AUDIENCE, INTERNAL_AUDIENCE) : audiences;
    }
}
