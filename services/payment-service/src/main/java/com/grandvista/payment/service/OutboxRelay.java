package com.grandvista.payment.service;

import com.grandvista.payment.config.PaymentProperties;
import com.grandvista.payment.domain.OutboxEvent;
import com.grandvista.payment.repository.OutboxEventRepository;
import com.grandvista.platform.web.CorrelationId;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivers outbox events with at-least-once semantics through the configured {@link OutboxPublisher}
 * (RabbitMQ by default, or HTTP straight to the booking-service): one event per short transaction,
 * exponential backoff on failure, dead-lettering after the configured number of attempts. A transport failure
 * stops the current run so an unavailable broker or consumer is not hammered.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int MAX_PER_RUN = 50;

    private final OutboxEventRepository outbox;
    private final OutboxPublisher publisher;
    private final PaymentProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;

    public OutboxRelay(OutboxEventRepository outbox, OutboxPublisher publisher, PaymentProperties properties,
            TransactionTemplate tx, Clock clock) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.properties = properties;
        this.tx = tx;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${gv.payment.outbox.relay-interval:2s}", initialDelayString = "${gv.payment.outbox.initial-delay:15s}")
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

    /** @return true when an event was handled and more may be pending; false when idle or the transport is down */
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
            publisher.publish(event);
            event.markSent(clock.instant());
            log.info("Delivered {} for payment {} via {}", event.getEventType(), event.getAggregateId(), publisher.name());
            return true;
        } catch (OutboxPublisher.RejectedDeliveryException ex) {
            event.markDead(ex.getMessage());
            log.error("Outbox event {} rejected by {} ({}); dead-lettered", event.getId(), publisher.name(), ex.getMessage());
            return true;
        } catch (OutboxPublisher.RetryableDeliveryException ex) {
            event.markFailed(ex.getMessage(), properties.outbox().maxAttempts(), properties.outbox().maxBackoff(),
                    clock.instant());
            log.warn("Outbox delivery of {} via {} failed (attempt {}): {}", event.getId(), publisher.name(),
                    event.getAttempts(), ex.getMessage());
            return false;
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }
}
