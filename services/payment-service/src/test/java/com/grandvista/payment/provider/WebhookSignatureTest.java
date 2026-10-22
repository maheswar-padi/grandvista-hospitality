package com.grandvista.payment.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WebhookSignatureTest {

    private static final String SECRET = "a".repeat(40);
    private final byte[] body = "{\"id\":\"evt_1\"}".getBytes(StandardCharsets.UTF_8);
    private final Instant now = Instant.parse("2026-11-01T10:00:00Z");

    @Test
    void validSignatureVerifies() {
        String header = WebhookSignature.sign(SECRET, now.getEpochSecond(), body);
        assertThat(WebhookSignature.verify(SECRET, header, body, now, Duration.ofMinutes(5))).isTrue();
    }

    @Test
    void rejectsTamperingWrongSecretReplayAndGarbage() {
        String header = WebhookSignature.sign(SECRET, now.getEpochSecond(), body);
        byte[] tampered = "{\"id\":\"evt_2\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(WebhookSignature.verify(SECRET, header, tampered, now, Duration.ofMinutes(5))).isFalse();
        assertThat(WebhookSignature.verify("b".repeat(40), header, body, now, Duration.ofMinutes(5))).isFalse();
        assertThat(WebhookSignature.verify(SECRET, header, body, now.plus(Duration.ofMinutes(6)), Duration.ofMinutes(5)))
                .isFalse();
        assertThat(WebhookSignature.verify(SECRET, "t=abc,v1=00", body, now, Duration.ofMinutes(5))).isFalse();
        assertThat(WebhookSignature.verify(SECRET, null, body, now, Duration.ofMinutes(5))).isFalse();
        assertThat(WebhookSignature.verify("", header, body, now, Duration.ofMinutes(5))).isFalse();
    }
}
