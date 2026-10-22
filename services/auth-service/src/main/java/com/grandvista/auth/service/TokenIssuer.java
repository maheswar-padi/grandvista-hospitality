package com.grandvista.auth.service;

import com.grandvista.auth.config.AuthProperties;
import com.grandvista.auth.domain.Role;
import com.grandvista.auth.domain.UserAccount;
import com.grandvista.platform.security.JwtProperties;
import com.grandvista.platform.security.Roles;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/** Mints RS256-signed access tokens for people and for backend services. */
@Component
public class TokenIssuer {

    public record IssuedToken(String value, Instant expiresAt, long expiresInSeconds) {
    }

    private final JwtEncoder encoder;
    private final JwtProperties jwtProperties;
    private final AuthProperties authProperties;
    private final Clock clock;

    public TokenIssuer(JwtEncoder encoder, JwtProperties jwtProperties, AuthProperties authProperties, Clock clock) {
        this.encoder = encoder;
        this.jwtProperties = jwtProperties;
        this.authProperties = authProperties;
        this.clock = clock;
    }

    public IssuedToken issueAccessToken(UserAccount user) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(authProperties.accessTokenTtl());
        List<String> roles = user.getRoles().stream().map(Role::name).sorted().toList();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .audience(List.of(JwtProperties.PUBLIC_AUDIENCE))
                .subject(user.getId().toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .notBefore(now)
                .expiresAt(expiresAt)
                .claim("email", user.getEmail())
                .claim("name", user.getFullName())
                .claim("roles", roles)
                .build();
        return encode(claims, now, expiresAt);
    }

    public IssuedToken issueServiceToken(String clientId) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(authProperties.serviceTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .audience(List.of(JwtProperties.INTERNAL_AUDIENCE))
                .subject("service:" + clientId)
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .notBefore(now)
                .expiresAt(expiresAt)
                .claim("roles", List.of(Roles.SERVICE))
                .build();
        return encode(claims, now, expiresAt);
    }

    private IssuedToken encode(JwtClaimsSet claims, Instant now, Instant expiresAt) {
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).type("JWT").build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(value, expiresAt, expiresAt.getEpochSecond() - now.getEpochSecond());
    }
}
