package com.grandvista.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grandvista.auth.config.AuthProperties;
import com.grandvista.platform.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class ServiceClientAuthenticatorTest {

    private static final String SECRET = "s".repeat(40);

    private final ServiceClientAuthenticator authenticator = new ServiceClientAuthenticator(new AuthProperties(
            null, null, null, null, null, null, null,
            List.of(new AuthProperties.ServiceClient("booking-service", SECRET),
                    new AuthProperties.ServiceClient("weak-client", "too-short"))));

    private static String basic(String id, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void acceptsConfiguredClient() {
        assertThat(authenticator.authenticate(basic("booking-service", SECRET))).isEqualTo("booking-service");
    }

    @Test
    void rejectsWrongSecretUnknownClientWeakSecretAndMalformedHeaders() {
        for (String header : new String[] {basic("booking-service", SECRET + "x"), basic("other", SECRET),
                basic("weak-client", "too-short"), "Bearer abc", "Basic !!!", null}) {
            assertThatThrownBy(() -> authenticator.authenticate(header)).isInstanceOf(ApiException.class);
        }
    }

    @Test
    void masksEmailsInLogs() {
        assertThat(AdminBootstrap.mask("admin@example.test")).isEqualTo("a***@example.test");
    }
}
