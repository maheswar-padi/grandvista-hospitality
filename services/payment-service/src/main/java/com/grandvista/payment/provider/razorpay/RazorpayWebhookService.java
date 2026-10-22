package com.grandvista.payment.provider.razorpay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grandvista.payment.domain.Payment;
import com.grandvista.payment.domain.WebhookEvent;
import com.grandvista.payment.provider.PaymentProvider;
import com.grandvista.payment.repository.PaymentRepository;
import com.grandvista.payment.repository.WebhookEventRepository;
import com.grandvista.payment.service.PaymentAudit;
import com.grandvista.payment.service.PaymentService;
import com.grandvista.payment.service.WebhookService.Result;
import com.grandvista.platform.error.ApiException;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Razorpay webhooks: {@code X-Razorpay-Signature} (HMAC-SHA256 of the raw body with the webhook secret) is
 * verified first, events are de-duplicated by {@code X-Razorpay-Event-Id}, and only orders created by this
 * service are acted on.
 * <ul>
 *   <li>{@code payment.captured} / {@code order.paid}: the payment SUCCEEDED (also when the browser closed before
 *       confirming). If our payment was already voided or failed, the captured money is refunded in full.</li>
 *   <li>{@code payment.failed}: recorded only — the customer may retry within the same Razorpay Checkout; an unpaid
 *       booking expires with its hold.</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(name = "gv.payment.provider", havingValue = "razorpay")
public class RazorpayWebhookService {

    private static final Logger log = LoggerFactory.getLogger(RazorpayWebhookService.class);
    private static final String PROVIDER = RazorpayPaymentProvider.NAME;

    private final RazorpayProperties properties;
    private final PaymentProvider provider;
    private final PaymentRepository payments;
    private final WebhookEventRepository webhookEvents;
    private final PaymentService paymentService;
    private final PaymentAudit audit;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final Clock clock;

    public RazorpayWebhookService(RazorpayProperties properties, PaymentProvider provider, PaymentRepository payments,
            WebhookEventRepository webhookEvents, PaymentService paymentService, PaymentAudit audit,
            ObjectMapper objectMapper, TransactionTemplate tx, Clock clock) {
        this.properties = properties;
        this.provider = provider;
        this.payments = payments;
        this.webhookEvents = webhookEvents;
        this.paymentService = paymentService;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.tx = tx;
        this.clock = clock;
    }

    private enum Action { NONE, APPLY_SUCCESS, REFUND_ORPHAN }

    private record Decision(Result result, Action action, Payment payment, String providerPaymentId, BigDecimal amount) {
    }

    public Result handle(byte[] rawBody, String signature, String eventIdHeader) {
        if (!RazorpaySignatures.verifyWebhook(rawBody, signature, properties.webhookSecret())) {
            log.warn("Rejected Razorpay webhook with an invalid signature");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE", "Webhook signature verification failed.");
        }
        JsonNode event;
        try {
            event = objectMapper.readTree(rawBody);
        } catch (IOException ex) {
            throw ApiException.badRequest("MALFORMED_WEBHOOK", "Webhook payload is not valid JSON.");
        }
        String type = event.path("event").asText(null);
        if (type == null) {
            throw ApiException.badRequest("MALFORMED_WEBHOOK", "Webhook payload has no event type.");
        }
        String eventId = eventIdHeader != null && !eventIdHeader.isBlank() ? eventIdHeader : "sha256:" + sha256(rawBody).substring(0, 60);
        JsonNode entity = event.path("payload").path("payment").path("entity");

        Decision decision = tx.execute(status -> decide(eventId, type, entity, rawBody));
        if (decision.action() == Action.APPLY_SUCCESS) {
            paymentService.markPendingFromProvider(decision.payment().getId(), "WEBHOOK " + eventId);
            paymentService.applyOutcome(decision.payment().getId(), new PaymentProvider.ChargeResult(
                    PaymentProvider.Outcome.SUCCEEDED, decision.providerPaymentId(), null, null), "WEBHOOK " + eventId);
        } else if (decision.action() == Action.REFUND_ORPHAN) {
            refundOrphan(decision);
            // Recorded only after the refund was issued or definitively refused, so an unreachable provider
            // makes Razorpay redeliver this webhook instead of losing the refund.
            tx.executeWithoutResult(status -> record(eventId, type, rawBody, Result.PROCESSED));
        }
        return decision.result();
    }

    private Decision decide(String eventId, String type, JsonNode entity, byte[] rawBody) {
        if (webhookEvents.existsByProviderAndProviderEventId(PROVIDER, eventId)) {
            log.info("Duplicate Razorpay webhook {} ignored", eventId);
            return new Decision(Result.DUPLICATE, Action.NONE, null, null, null);
        }
        String orderId = entity.path("order_id").asText(null);
        Optional<Payment> found = orderId == null ? Optional.empty() : payments.findByProviderOrderIdForUpdate(PROVIDER, orderId);
        Decision decision;
        if (found.isEmpty()) {
            log.info("Razorpay webhook {} ({}) is not for an order of this service; ignored", eventId, type);
            decision = new Decision(Result.IGNORED, Action.NONE, null, null, null);
        } else {
            decision = decideFor(found.get(), type, entity);
        }
        if (decision.action() != Action.REFUND_ORPHAN) {
            record(eventId, type, rawBody, decision.result());
        }
        return decision;
    }

    private void record(String eventId, String type, byte[] rawBody, Result result) {
        webhookEvents.save(new WebhookEvent(PROVIDER, eventId, type.length() > 60 ? type.substring(0, 60) : type,
                sha256(rawBody), result == Result.PROCESSED ? "PROCESSED" : "IGNORED", clock.instant()));
    }

    private Decision decideFor(Payment payment, String type, JsonNode entity) {
        String providerPaymentId = entity.path("id").asText(null);
        boolean captured = (type.equals("payment.captured") || type.equals("order.paid"))
                && "captured".equals(entity.path("status").asText());
        if (!captured) {
            if (type.equals("payment.failed")) {
                audit.record(payment.getId(), "PROVIDER_ATTEMPT_FAILED", payment.getStatus(), payment.getStatus(),
                        providerPaymentId + " " + entity.path("error_code").asText(""));
            }
            return new Decision(Result.IGNORED, Action.NONE, payment, providerPaymentId, null);
        }
        long minor = entity.path("amount").asLong(-1);
        if (minor != RazorpayPaymentProvider.toMinorUnits(payment.getAmount())
                || !payment.getCurrency().equalsIgnoreCase(entity.path("currency").asText(""))) {
            log.error("Razorpay capture {} for payment {} has a different amount/currency; not applied", providerPaymentId,
                    payment.getId());
            audit.record(payment.getId(), "PROVIDER_AMOUNT_MISMATCH", payment.getStatus(), payment.getStatus(), providerPaymentId);
            return new Decision(Result.IGNORED, Action.NONE, payment, providerPaymentId, null);
        }
        BigDecimal amount = BigDecimal.valueOf(minor, 2);
        return switch (payment.getStatus()) {
            case CREATED, PENDING -> new Decision(Result.PROCESSED, Action.APPLY_SUCCESS, payment, providerPaymentId, amount);
            case CANCELLED, FAILED -> new Decision(Result.PROCESSED, Action.REFUND_ORPHAN, payment, providerPaymentId, amount);
            default -> new Decision(Result.IGNORED, Action.NONE, payment, providerPaymentId, amount); // already captured
        };
    }

    /** Money captured for a payment we had already voided or failed: give it back, never confirm a booking. */
    private void refundOrphan(Decision d) {
        String key = "orphan-" + d.providerPaymentId();
        try {
            var result = provider.refund(new PaymentProvider.RefundRequest(key, d.providerPaymentId(), d.amount()));
            tx.executeWithoutResult(status -> audit.record(d.payment().getId(),
                    result.succeeded() ? "ORPHAN_CAPTURE_REFUNDED" : "ORPHAN_CAPTURE_REFUND_FAILED",
                    d.payment().getStatus(), d.payment().getStatus(),
                    d.providerPaymentId() + " " + d.amount() + " " + (result.succeeded() ? result.providerReference() : result.failureMessage())));
            log.warn("Captured Razorpay payment {} for {} payment {}: refund {}", d.providerPaymentId(),
                    d.payment().getStatus(), d.payment().getId(), result.succeeded() ? "issued" : "FAILED");
        } catch (PaymentProvider.ProviderUnavailableException ex) {
            log.warn("Refund of orphan capture {} could not reach Razorpay; asking Razorpay to redeliver", d.providerPaymentId());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_UNAVAILABLE", "Retry later.");
        }
    }

    private static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
