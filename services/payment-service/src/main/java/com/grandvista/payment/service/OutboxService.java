package com.grandvista.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grandvista.payment.domain.OutboxEvent;
import com.grandvista.payment.domain.Payment;
import com.grandvista.payment.repository.OutboxEventRepository;
import com.grandvista.platform.messaging.EventTopology;
import com.grandvista.platform.web.CorrelationId;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Writes integration events in the same transaction as the payment state change (transactional outbox). */
@Service
public class OutboxService {

    public static final String PAYMENT_SUCCEEDED = EventTopology.PAYMENT_SUCCEEDED;
    public static final String PAYMENT_FAILED = EventTopology.PAYMENT_FAILED;

    /** Versioned event contract consumed by the booking-service. */
    public record PaymentEventMessage(UUID eventId, String eventType, int schemaVersion, UUID paymentId, UUID bookingId,
                                      String status, BigDecimal amount, String currency, String failureCode,
                                      String provider, Instant occurredAt) {
    }

    private final OutboxEventRepository outbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OutboxService(OutboxEventRepository outbox, ObjectMapper objectMapper, Clock clock) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String eventType, Payment payment) {
        UUID eventId = UUID.randomUUID();
        Instant now = clock.instant();
        var message = new PaymentEventMessage(eventId, eventType, 1, payment.getId(), payment.getBookingId(),
                payment.getStatus().name(), payment.getAmount(), payment.getCurrency(), payment.getFailureCode(),
                payment.getProvider(), now);
        try {
            outbox.save(new OutboxEvent(eventId, payment.getId(), eventType, objectMapper.writeValueAsString(message),
                    CorrelationId.current().orElse(null), now));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
