package com.grandvista.notification.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grandvista.notification.service.NotificationService;
import com.grandvista.platform.messaging.EventTopology;
import com.grandvista.platform.web.CorrelationId;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes every booking and payment event from {@code notification.events}. Failures (SMTP down, contact not
 * yet known) are thrown, retried with exponential backoff by the listener container, then dead-lettered to
 * {@code notification.events.dlq}. Malformed messages are dead-lettered immediately.
 */
@Component
public class DomainEventListener {

    public static final String QUEUE = "notification.events";
    private static final Logger log = LoggerFactory.getLogger(DomainEventListener.class);

    private final NotificationService notifications;
    private final ObjectMapper objectMapper;

    public DomainEventListener(NotificationService notifications, ObjectMapper objectMapper) {
        this.notifications = notifications;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = QUEUE, autoStartup = "${gv.notification.consume-enabled:true}",
            concurrency = "${gv.notification.consumers:2}")
    public void onMessage(Message message) {
        String correlationId = message.getMessageProperties().getHeader(EventTopology.CORRELATION_HEADER);
        MDC.put(CorrelationId.MDC_KEY, correlationId == null ? CorrelationId.newId() : correlationId);
        try {
            JsonNode body = read(message);
            String type = body.path("eventType").asText("");
            if (body.path("eventId").isMissingNode() || body.path("bookingId").isMissingNode()) {
                throw new AmqpRejectAndDontRequeueException("Event without eventId/bookingId");
            }
            NotificationService.Result result;
            if (type.startsWith("BOOKING_") || type.equals(EventTopology.HOLD_EXPIRED)) {
                result = notifications.onBookingEvent(objectMapper.treeToValue(body, Events.BookingEvent.class));
            } else if (type.startsWith("PAYMENT_")) {
                result = notifications.onPaymentEvent(objectMapper.treeToValue(body, Events.PaymentEvent.class));
            } else {
                log.info("Ignoring event of unknown type '{}'", type);
                return;
            }
            log.info("Event {} {}: {}", body.path("eventId").asText(), type, result);
        } catch (IOException ex) {
            throw new AmqpRejectAndDontRequeueException("Malformed event: " + ex.getMessage());
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }

    private JsonNode read(Message message) {
        try {
            return objectMapper.readTree(message.getBody());
        } catch (IOException ex) {
            throw new AmqpRejectAndDontRequeueException("Malformed event: " + ex.getMessage());
        }
    }
}
