package com.grandvista.payment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grandvista.payment.config.PaymentProperties;
import com.grandvista.payment.config.WebhookSecretHolder;
import com.grandvista.payment.domain.Payment;
import com.grandvista.payment.domain.WebhookEvent;
import com.grandvista.payment.provider.PaymentProvider;
import com.grandvista.payment.provider.WebhookSignature;
import com.grandvista.payment.provider.simulated.SimulatedPaymentProvider;
import com.grandvista.payment.repository.PaymentRepository;
import com.grandvista.payment.repository.WebhookEventRepository;
import com.grandvista.platform.error.ApiException;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Processes provider callbacks. The signature is verified over the raw request bytes before anything is
 * parsed; replays are rejected by the timestamp tolerance and duplicates by the stored provider event id.
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    public enum Result { PROCESSED, DUPLICATE, IGNORED }

    private final WebhookSecretHolder secret;
    private final PaymentProperties properties;
    private final WebhookEventRepository webhookEvents;
    private final PaymentRepository payments;
    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WebhookService(WebhookSecretHolder secret, PaymentProperties properties, WebhookEventRepository webhookEvents,
            PaymentRepository payments, PaymentService paymentService, ObjectMapper objectMapper, Clock clock) {
        this.secret = secret;
        this.properties = properties;
        this.webhookEvents = webhookEvents;
        this.payments = payments;
        this.paymentService = paymentService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public Result handleSimulated(byte[] rawBody, String signatureHeader) {
        if (!WebhookSignature.verify(secret.secret(), signatureHeader, rawBody, clock.instant(),
                properties.webhookTolerance())) {
            log.warn("Rejected webhook with invalid or expired signature");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE", "Webhook signature verification failed.");
        }
        JsonNode event;
        try {
            event = objectMapper.readTree(rawBody);
        } catch (IOException ex) {
            throw ApiException.badRequest("MALFORMED_WEBHOOK", "Webhook payload is not valid JSON.");
        }
        String eventId = text(event, "id");
        String type = text(event, "type");
        JsonNode data = event.path("data");
        String reference = text(data, "reference");
        if (eventId == null || type == null || reference == null) {
            throw ApiException.badRequest("MALFORMED_WEBHOOK", "Webhook payload is missing required fields.");
        }
        String provider = SimulatedPaymentProvider.NAME;
        if (webhookEvents.existsByProviderAndProviderEventId(provider, eventId)) {
            log.info("Duplicate webhook {} ignored", eventId);
            return Result.DUPLICATE;
        }

        Optional<Payment> payment = payments.findByProviderReferenceForUpdate(provider, reference)
                .or(() -> paymentIdFromMetadata(data).flatMap(payments::findByIdForUpdate));
        if (payment.isEmpty()) {
            // Not yet known (e.g. our own response handling is still committing): ask the provider to retry.
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_NOT_YET_KNOWN", "Retry later.");
        }
        PaymentProvider.Outcome outcome = switch (type) {
            case "charge.succeeded" -> PaymentProvider.Outcome.SUCCEEDED;
            case "charge.failed" -> PaymentProvider.Outcome.FAILED;
            default -> null;
        };
        Result result;
        if (outcome == null) {
            result = Result.IGNORED;
        } else {
            var before = payment.get().getStatus();
            String failureCode = text(data, "failureCode");
            Payment updated = paymentService.applyOutcome(payment.get().getId(), new PaymentProvider.ChargeResult(outcome,
                    reference, failureCode, failureCode == null ? null : "[SIMULATED] payment declined: " + failureCode),
                    "WEBHOOK " + eventId);
            result = updated.getStatus() != before ? Result.PROCESSED : Result.IGNORED;
        }
        webhookEvents.save(new WebhookEvent(provider, eventId, type, sha256(rawBody), result.name(), clock.instant()));
        return result;
    }

    private static Optional<UUID> paymentIdFromMetadata(JsonNode data) {
        try {
            String id = text(data.path("metadata"), "paymentId");
            return id == null ? Optional.empty() : Optional.of(UUID.fromString(id));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
