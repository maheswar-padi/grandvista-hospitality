package com.grandvista.payment.service;

import com.grandvista.payment.config.PaymentProperties;
import com.grandvista.payment.domain.OutboxEvent;
import com.grandvista.payment.repository.OutboxEventRepository;
import com.grandvista.platform.web.CorrelationId;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Delivers outbox events to the booking-service (discovered through Eureka) with at-least-once semantics:
 * one event per short transaction, exponential backoff on failure, dead-lettering after the configured
 * number of attempts. A transport failure stops the current run so an unavailable consumer is not hammered.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int MAX_PER_RUN = 50;

    private final OutboxEventRepository outbox;
    private final RestClient bookingClient;
    private final PaymentProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;

    public OutboxRelay(OutboxEventRepository outbox, RestClient bookingServiceClient, PaymentProperties properties,
            TransactionTemplate tx, Clock clock) {
        this.outbox = outbox;
        this.bookingClient = bookingServiceClient;
        this.properties = properties;
        this.tx = tx;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${gv.payment.outbox.relay-interval:2s}", initialDelayString = "15s")
    public void relay() {
        for (int i = 0; i < MAX_PER_RUN; i++) {
            Boolean delivered;
            try {
                delivered = tx.execute(status -> deliverNext());
            } catch (RuntimeException ex) {
                log.warn("Outbox relay run aborted: {}", ex.getMessage());
                return;
            }
            if (delivered == null || !delivered) {
                return;
            }
        }
    }

    /** @return true when an event was delivered and more may be pending; false when idle or consumer down */
    Boolean deliverNext() {
        var claimed = outbox.claimNext(clock.instant());
        if (claimed.isEmpty()) {
            return false;
        }
        OutboxEvent event = claimed.get(0);
        if (event.getCorrelationId() != null) {
            MDC.put(CorrelationId.MDC_KEY, event.getCorrelationId());
        }
        try {
            bookingClient.post().uri("/internal/v1/payment-events")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(CorrelationId.HEADER, event.getCorrelationId() == null ? CorrelationId.newId() : event.getCorrelationId())
                    .body(event.getPayload())
                    .retrieve().toBodilessEntity();
            event.markSent(clock.instant());
            log.info("Delivered {} for payment {} to booking-service", event.getEventType(), event.getAggregateId());
            return true;
        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode().value() == 401 || ex.getStatusCode().value() == 429) {
                event.markFailed(ex.getStatusCode().toString(), properties.outbox().maxAttempts(),
                        properties.outbox().maxBackoff(), clock.instant());
            } else {
                // A 4xx means the consumer rejected the contract: retrying the same message cannot succeed.
                event.markDead(ex.getStatusCode() + " " + ex.getResponseBodyAsString());
                log.error("Outbox event {} rejected by booking-service ({}); dead-lettered", event.getId(), ex.getStatusCode());
            }
            return true;
        } catch (RestClientException | IllegalStateException ex) {
            event.markFailed(ex.getMessage(), properties.outbox().maxAttempts(), properties.outbox().maxBackoff(),
                    clock.instant());
            log.warn("Outbox delivery of {} failed (attempt {}): {}", event.getId(), event.getAttempts(), ex.getMessage());
            return false;
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }
}
