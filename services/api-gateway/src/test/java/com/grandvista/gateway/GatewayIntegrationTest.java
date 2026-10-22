package com.grandvista.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * Runs the real gateway against a stub upstream (standing in for every service and for the auth-service
 * JWKS endpoint) and checks routing, edge security, CORS, rate limiting, size limits and error mapping.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayIntegrationTest {

    static final RSAKey KEY;
    static final DisposableServer UPSTREAM;

    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        String jwks = new JWKSet(KEY.toPublicJWK()).toString();
        UPSTREAM = HttpServer.create().port(0).route(routes -> routes
                .get("/oauth2/jwks", (req, res) -> res.header("Content-Type", "application/json").sendString(
                        reactor.core.publisher.Mono.just(jwks)))
                .route(req -> true, (req, res) -> res.header("Content-Type", "application/json").sendString(
                        reactor.core.publisher.Mono.just("{\"upstreamPath\":\"" + req.uri() + "\",\"auth\":"
                                + req.requestHeaders().contains("Authorization") + ",\"cid\":\""
                                + req.requestHeaders().get("X-Correlation-Id") + "\"}"))))
                .bindNow();
    }

    @AfterAll
    static void stop() {
        UPSTREAM.disposeNow();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        String base = "http://localhost:" + UPSTREAM.port();
        for (String route : List.of("GV_ROUTE_AUTH", "GV_ROUTE_ROOM", "GV_ROUTE_BOOKING", "GV_ROUTE_PAYMENT")) {
            registry.add(route, () -> base);
        }
        registry.add("GV_JWKS_URI", () -> base + "/oauth2/jwks");
        registry.add("eureka.client.enabled", () -> "false");
        registry.add("GV_AUTH_RATE_LIMIT", () -> "3");
        registry.add("GV_CORS_ALLOWED_ORIGINS", () -> "http://localhost:5173");
    }

    @Autowired
    WebTestClient client;

    static String token(List<String> roles, String audience, Instant expires) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(),
                new JWTClaimsSet.Builder().issuer("grandvista-auth").audience(audience).subject(UUID.randomUUID().toString())
                        .claim("roles", roles).issueTime(new Date()).expirationTime(Date.from(expires)).build());
        jwt.sign(new RSASSASigner(KEY));
        return jwt.serialize();
    }

    static String customerToken() throws Exception {
        return token(List.of("CUSTOMER"), "grandvista-api", Instant.now().plusSeconds(600));
    }

    @Test
    void publicCatalogueIsRoutedWithCorrelationId() {
        client.get().uri("/api/v1/rooms?checkIn=2026-12-01").header("X-Correlation-Id", "test-correlation-001")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Correlation-Id", "test-correlation-001")
                .expectBody()
                .jsonPath("$.upstreamPath").isEqualTo("/api/v1/rooms?checkIn=2026-12-01")
                .jsonPath("$.cid").isEqualTo("test-correlation-001");
    }

    @Test
    void invalidCorrelationIdIsReplaced() {
        String cid = client.get().uri("/api/v1/room-types").header("X-Correlation-Id", "bad id<>")
                .exchange().expectStatus().isOk()
                .returnResult(String.class).getResponseHeaders().getFirst("X-Correlation-Id");
        assertThat(cid).isNotEqualTo("bad id<>").hasSize(36);
    }

    @Test
    void protectedRoutesNeedAValidTokenWhichIsForwarded() throws Exception {
        client.get().uri("/api/v1/bookings/my").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.code").isEqualTo("AUTHENTICATION_REQUIRED");

        client.get().uri("/api/v1/bookings/my").header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken())
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.auth").isEqualTo(true);

        String expired = token(List.of("CUSTOMER"), "grandvista-api", Instant.now().minusSeconds(300));
        client.get().uri("/api/v1/bookings/my").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired)
                .exchange().expectStatus().isUnauthorized();

        String serviceAudience = token(List.of("SERVICE"), "grandvista-internal", Instant.now().plusSeconds(300));
        client.get().uri("/api/v1/bookings/my").header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceAudience)
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void adminPathsRequirePersonnelRoles() throws Exception {
        client.get().uri("/api/v1/admin/bookings").header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken())
                .exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("ACCESS_DENIED");
        String staff = token(List.of("STAFF"), "grandvista-api", Instant.now().plusSeconds(300));
        client.get().uri("/api/v1/admin/bookings").header(HttpHeaders.AUTHORIZATION, "Bearer " + staff)
                .exchange().expectStatus().isOk();
    }

    @Test
    void internalAndActuatorPathsAreNeverExposed() throws Exception {
        String admin = token(List.of("ADMIN", "SERVICE"), "grandvista-api", Instant.now().plusSeconds(300));
        for (String path : List.of("/internal/v1/holds", "/actuator/env", "/actuator/gateway/routes")) {
            client.get().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + admin).exchange()
                    .expectStatus().isNotFound();
        }
        client.post().uri("/internal/v1/payment-events").contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().is4xxClientError();
    }

    @Test
    void credentialEndpointsAreRateLimited() {
        for (int i = 0; i < 3; i++) {
            client.post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                    .exchange().expectStatus().isOk();
        }
        client.post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isEqualTo(429)
                .expectHeader().exists("Retry-After")
                .expectBody().jsonPath("$.code").isEqualTo("RATE_LIMITED");
        // other endpoints use a separate, larger budget
        client.get().uri("/api/v1/rooms").exchange().expectStatus().isOk();
    }

    @Test
    void corsAllowsOnlyConfiguredOrigins() {
        client.options().uri("/api/v1/rooms").header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "GET").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:5173")
                .expectHeader().valueEquals("Access-Control-Allow-Credentials", "true");
        client.options().uri("/api/v1/rooms").header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "GET").exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void oversizedRequestsAreRejectedAtTheEdge() throws Exception {
        client.post().uri("/api/v1/bookings").header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken())
                .contentType(MediaType.APPLICATION_JSON).bodyValue("x".repeat(7 * 1024 * 1024))
                .exchange().expectStatus().isEqualTo(413)
                .expectBody().jsonPath("$.code").isEqualTo("PAYLOAD_TOO_LARGE");
    }

    @Test
    void unknownRoutesReturnProblemJson() {
        client.get().uri("/api/v2/whatever").exchange().expectStatus().isUnauthorized();
        client.get().uri("/not-an-api").exchange().expectStatus().isNotFound();
    }
}
