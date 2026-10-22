package com.grandvista.platform.security;

import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/** Token validation and authority-mapping rules shared by every resource server. */
public final class JwtSupport {

    private JwtSupport() {
    }

    /** Signature is checked by the decoder; this adds expiry/not-before, issuer and audience checks. */
    public static OAuth2TokenValidator<Jwt> validator(JwtProperties properties) {
        OAuth2TokenValidator<Jwt> audience = jwt -> {
            List<String> tokenAudiences = jwt.getAudience();
            boolean accepted = tokenAudiences != null
                    && tokenAudiences.stream().anyMatch(properties.audiences()::contains);
            return accepted ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Unexpected audience", null));
        };
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()), audience);
    }

    /** Maps the {@code roles} claim to Spring authorities: {@code ADMIN -> ROLE_ADMIN}. */
    public static JwtAuthenticationConverter authenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(JwtSupport::authorities);
        return converter;
    }

    static Collection<GrantedAuthority> authorities(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null) {
            return List.of();
        }
        return roles.stream()
                .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    }
}
