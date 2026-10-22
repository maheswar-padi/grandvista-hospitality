package com.grandvista.gateway.config;

import com.grandvista.gateway.error.ProblemResponses;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Mono;

/**
 * Edge security. The gateway rejects obviously unauthorised traffic early (missing/invalid tokens,
 * non-personnel on admin paths, any /internal path) but is NOT the only line of defence: every service
 * re-validates the token and applies its own fine-grained rules.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    static final String[] PUBLIC_POST = {"/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh",
            "/api/v1/auth/logout", "/api/v1/payments/webhooks/**"};
    static final String[] PUBLIC_GET = {"/api/v1/rooms/**", "/api/v1/room-types/**", "/api/v1/amenities/**",
            "/api/v1/media/**", "/api/v1/payments/test-methods"};

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, ProblemResponses problems,
            ReactiveJwtDecoder decoder) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .cors(cors -> { })
                // let downstream services decide caching (e.g. immutable room images)
                .headers(h -> h.cache(ServerHttpSecurity.HeaderSpec.CacheSpec::disable))
                .authorizeExchange(ex -> ex
                        .pathMatchers(HttpMethod.OPTIONS).permitAll()
                        .pathMatchers("/internal/**").denyAll()
                        .pathMatchers(HttpMethod.POST, PUBLIC_POST).permitAll()
                        .pathMatchers(HttpMethod.GET, PUBLIC_GET).permitAll()
                        .pathMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .pathMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**", "/webjars/**",
                                "/api-docs/**").permitAll()
                        .pathMatchers("/api/v1/admin/**").hasAnyRole("ADMIN", "STAFF")
                        .pathMatchers("/api/v1/**").authenticated()
                        .anyExchange().denyAll())
                .oauth2ResourceServer(rs -> rs
                        .jwt(jwt -> jwt.jwtDecoder(decoder).jwtAuthenticationConverter(authenticationConverter()))
                        .authenticationEntryPoint((exchange, e) -> problems.write(exchange, HttpStatus.UNAUTHORIZED,
                                "AUTHENTICATION_REQUIRED", "A valid access token is required."))
                        .accessDeniedHandler((exchange, e) -> problems.write(exchange, HttpStatus.FORBIDDEN,
                                "ACCESS_DENIED", "You do not have permission to perform this action.")))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((exchange, ex2) -> isApi(exchange)
                                ? problems.write(exchange, HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED",
                                        "A valid access token is required.")
                                : notFound(problems, exchange))
                        .accessDeniedHandler((exchange, ex2) -> isApi(exchange)
                                ? problems.write(exchange, HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                                        "You do not have permission to perform this action.")
                                : notFound(problems, exchange)))
                .build();
    }

    /** Paths outside the public API (e.g. /internal, actuators) are reported as non-existent, not forbidden. */
    private static boolean isApi(org.springframework.web.server.ServerWebExchange exchange) {
        return exchange.getRequest().getPath().value().startsWith("/api/");
    }

    private static Mono<Void> notFound(ProblemResponses problems, org.springframework.web.server.ServerWebExchange exchange) {
        return problems.write(exchange, HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found.");
    }

    /** RS256 tokens verified against the auth-service JWKS, with issuer, expiry and audience checks. */
    @Bean
    ReactiveJwtDecoder reactiveJwtDecoder(GatewayProperties properties) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
        OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience() != null && jwt.getAudience().contains("grandvista-api")
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Unexpected audience", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.jwtIssuer()), audience));
        return decoder;
    }

    static Converter<Jwt, Mono<AbstractAuthenticationToken>> authenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<String> roles = jwt.getClaimAsStringList("roles");
            Collection<GrantedAuthority> authorities = new ArrayList<>();
            if (roles != null) {
                roles.forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
            }
            return authorities;
        });
        return new ReactiveJwtAuthenticationConverterAdapter(converter);
    }

    /** Explicit allow-list of browser origins; credentials allowed so the refresh-token cookie works. */
    @Bean
    CorsConfigurationSource corsConfigurationSource(GatewayProperties properties) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.corsAllowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Requested-With",
                "X-Correlation-Id", "Accept"));
        cors.setExposedHeaders(List.of("X-Correlation-Id", "Retry-After", "Location"));
        cors.setAllowCredentials(true);
        cors.setMaxAge(Duration.ofHours(1));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }
}
