package com.grandvista.booking.messaging;

import com.grandvista.booking.domain.OutboxEvent;
import com.grandvista.booking.repository.OutboxEventRepository;
import com.grandvista.platform.messaging.ConfirmedRabbitPublisher;
import com.grandvista.platform.web.CorrelationId;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes booking events from the outbox to RabbitMQ (at-least-once): one event per short transaction,
 * claimed with {@code FOR UPDATE SKIP LOCKED} so several replicas can run it, exponential backoff when the broker
 * is unavailable or nobody is bound yet, and dead-lettering after {@code max-attempts}.
 */
@Component
@ConditionalOnProperty(name = "gv.booking.events.publish-enabled", havingValue = "true", matchIfMissing = true)
public class BookingOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(BookingOutboxRelay.class);
    private static final int MAX_PER_RUN = 50;

    private final OutboxEventRepository outbox;
    private final ConfirmedRabbitPublisher publisher;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final int maxAttempts;
    private final Duration maxBackoff;

    public BookingOutboxRelay(OutboxEventRepository outbox, ConfirmedRabbitPublisher publisher, TransactionTemplate tx,
            Clock clock, @Value("${gv.booking.events.max-attempts:25}") int maxAttempts,
            @Value("${gv.booking.events.max-backoff:5m}") Duration maxBackoff) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.tx = tx;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
        this.maxBackoff = maxBackoff;
    }

    @Scheduled(fixedDelayString = "${gv.booking.events.relay-interval:1s}", initialDelayString = "${gv.booking.events.initial-delay:10s}")
    public void relay() {
        for (int i = 0; i < MAX_PER_RUN; i++) {
            Boolean published;
            try {
                published = tx.execute(status -> publishNext());
            } catch (RuntimeException ex) {
                log.warn("Booking event relay run aborted: {}", ex.getMessage());
                return;
            }
            if (published == null || !published) {
                return;
            }
        }
    }

    /** @return true when an event was published and more may be pending; false when idle or the broker is down */
    public Boolean publishNext() {
        var claimed = outbox.claimNext(clock.instant());
        if (claimed.isEmpty()) {
            return false;
        }
        OutboxEvent event = claimed.get(0);
        if (event.getCorrelationId() != null) {
            MDC.put(CorrelationId.MDC_KEY, event.getCorrelationId());
        }
        try {
            publisher.publish(event.getId().toString(), event.getEventType(), event.getPayload(), event.getCorrelationId());
            event.markSent(clock.instant());
            log.info("Published {} for booking {}", event.getEventType(), event.getAggregateId());
            return true;
        } catch (ConfirmedRabbitPublisher.PublishFailedException ex) {
            event.markFailed(ex.getMessage(), maxAttempts, maxBackoff, clock.instant());
            log.warn("Publishing {} failed (attempt {}): {}", event.getId(), event.getAttempts(), ex.getMessage());
            return false;
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }
}
