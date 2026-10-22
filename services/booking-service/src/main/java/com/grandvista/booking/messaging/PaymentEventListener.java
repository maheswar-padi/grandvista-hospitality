package com.grandvista.booking.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grandvista.booking.service.PaymentEventService;
import com.grandvista.booking.web.dto.BookingDtos.PaymentEventMessage;
import com.grandvista.platform.messaging.EventTopology;
import com.grandvista.platform.web.CorrelationId;
import jakarta.validation.Validator;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes PAYMENT_SUCCEEDED / PAYMENT_FAILED from the {@code booking.payment-events} queue and applies them
 * through the same idempotent {@link PaymentEventService} as the internal HTTP endpoint (inbox de-duplication by
 * event id). A transient failure (e.g. room-service down) is thrown, retried with backoff by the listener
 * container and finally dead-lettered; the booking recovery job also reconciles such bookings with the
 * payment-service. Malformed messages go straight to the dead-letter queue.
 */
@Component
public class PaymentEventListener {

    public static final String QUEUE = "booking.payment-events";
    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);

    private final PaymentEventService events;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public PaymentEventListener(PaymentEventService events, ObjectMapper objectMapper, Validator validator) {
        this.events = events;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    @RabbitListener(queues = QUEUE, autoStartup = "${gv.booking.events.consume-enabled:true}",
            concurrency = "${gv.booking.events.consumers:2}")
    public void onMessage(Message message) {
        String correlationId = message.getMessageProperties().getHeader(EventTopology.CORRELATION_HEADER);
        MDC.put(CorrelationId.MDC_KEY, correlationId == null ? CorrelationId.newId() : correlationId);
        try {
            PaymentEventMessage event = parse(message);
            PaymentEventService.Result result = events.handle(event);
            log.info("Payment event {} {} for booking {}: {}", event.eventId(), event.eventType(), event.bookingId(), result);
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }

    private PaymentEventMessage parse(Message message) {
        PaymentEventMessage event;
        try {
            event = objectMapper.readValue(message.getBody(), PaymentEventMessage.class);
        } catch (IOException ex) {
            throw new AmqpRejectAndDontRequeueException("Malformed payment event: " + ex.getMessage());
        }
        var violations = validator.validate(event);
        if (!violations.isEmpty()) {
            throw new AmqpRejectAndDontRequeueException("Invalid payment event: " + violations.iterator().next().getMessage());
        }
        return event;
    }
}
