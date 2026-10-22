package com.grandvista.payment.provider.simulated;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grandvista.payment.config.PaymentProperties;
import com.grandvista.payment.config.WebhookSecretHolder;
import com.grandvista.payment.provider.WebhookSignature;
import com.grandvista.platform.client.HttpClients;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Plays the role of the SIMULATED provider's webhook sender: settles pending charges and delivers signed
 * callbacks over real HTTP to the payment-service's public webhook endpoint, retrying non-2xx answers.
 */
@Component
@ConditionalOnProperty(name = "gv.payment.simulator.enabled", havingValue = "true", matchIfMissing = true)
public class SimulatedWebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(SimulatedWebhookDispatcher.class);

    private final SimulatedChargeRepository charges;
    private final TransactionTemplate tx;
    private final WebhookSecretHolder secret;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final RestClient http;
    private final String webhookUrl;

    public SimulatedWebhookDispatcher(SimulatedChargeRepository charges, TransactionTemplate tx, WebhookSecretHolder secret,
            ObjectMapper objectMapper, PaymentProperties properties, Clock clock) {
        this.charges = charges;
        this.tx = tx;
        this.secret = secret;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.webhookUrl = properties.simulator().webhookUrl();
        this.http = RestClient.builder()
                .requestFactory(HttpClients.requestFactory(Duration.ofSeconds(2), Duration.ofSeconds(5)))
                .build();
    }

    @Scheduled(fixedDelayString = "${gv.payment.simulator.dispatch-interval:1s}", initialDelayString = "10s")
    public void dispatch() {
        try {
            tx.executeWithoutResult(status -> charges.claimDueWebhooks(clock.instant()).forEach(this::deliver));
        } catch (RuntimeException ex) {
            log.warn("[SIMULATED PROVIDER] webhook dispatch failed: {}", ex.getMessage());
        }
    }

    private void deliver(SimulatedCharge charge) {
        charge.settle();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reference", charge.getReference());
        data.put("status", charge.getStatus());
        data.put("failureCode", charge.getFailureCode());
        data.put("metadata", Map.of("paymentId", charge.getIdempotencyKey()));
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", "evt_" + charge.getId().toString().replace("-", ""));
        event.put("type", "SUCCEEDED".equals(charge.getStatus()) ? "charge.succeeded" : "charge.failed");
        event.put("created", clock.instant().getEpochSecond());
        event.put("data", data);
        try {
            byte[] body = objectMapper.writeValueAsString(event).getBytes(StandardCharsets.UTF_8);
            String signature = WebhookSignature.sign(secret.secret(), clock.instant().getEpochSecond(), body);
            http.post().uri(webhookUrl).contentType(MediaType.APPLICATION_JSON)
                    .header(WebhookSignature.HEADER, signature).body(body).retrieve().toBodilessEntity();
            charge.markWebhookSent(clock.instant());
            log.info("[SIMULATED PROVIDER] webhook {} delivered for {}", event.get("type"), charge.getReference());
        } catch (RestClientException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            Instant retryAt = clock.instant().plusSeconds((long) Math.min(60, Math.pow(2, charge.getWebhookAttempts() + 1)));
            charge.markWebhookFailed(retryAt);
            log.warn("[SIMULATED PROVIDER] webhook delivery for {} failed ({}); retrying at {}", charge.getReference(),
                    ex.getMessage(), retryAt);
        }
    }
}
