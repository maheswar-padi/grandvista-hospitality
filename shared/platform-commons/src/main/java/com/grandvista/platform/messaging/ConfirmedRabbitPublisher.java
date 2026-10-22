package com.grandvista.platform.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Publishes one JSON event and waits for the broker's publisher confirm. The message is persistent and
 * published with {@code mandatory=true}: if no queue is bound yet (a consumer has never started) the broker
 * returns it, which is reported as a failure so the caller's outbox keeps the event and retries later.
 * Requires {@code spring.rabbitmq.publisher-confirm-type=correlated} and {@code publisher-returns=true}.
 */
public class ConfirmedRabbitPublisher {

    private final RabbitTemplate template;
    private final Duration confirmTimeout;

    public ConfirmedRabbitPublisher(RabbitTemplate template, Duration confirmTimeout) {
        this.template = template;
        this.confirmTimeout = confirmTimeout;
        this.template.setMandatory(true);
    }

    /** @throws PublishFailedException when the broker did not confirm the message (it should be retried) */
    public void publish(String eventId, String eventType, String jsonPayload, String correlationId)
            throws PublishFailedException {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        props.setMessageId(eventId);
        props.setType(eventType);
        props.setTimestamp(new Date());
        if (correlationId != null) {
            props.setHeader(EventTopology.CORRELATION_HEADER, correlationId);
        }
        Message message = new Message(jsonPayload.getBytes(StandardCharsets.UTF_8), props);
        CorrelationData correlation = new CorrelationData(eventId);
        try {
            template.send(EventTopology.EXCHANGE, EventTopology.routingKey(eventType), message, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (correlation.getReturned() != null) {
                throw new PublishFailedException("unroutable (no queue bound for " + EventTopology.routingKey(eventType)
                        + "): " + correlation.getReturned().getReplyText(), null);
            }
            if (!confirm.isAck()) {
                throw new PublishFailedException("broker refused the message: " + confirm.getReason(), null);
            }
        } catch (AmqpException ex) {
            throw new PublishFailedException("broker unavailable: " + ex.getMessage(), ex);
        } catch (TimeoutException ex) {
            throw new PublishFailedException("no publisher confirm within " + confirmTimeout, ex);
        } catch (ExecutionException ex) {
            throw new PublishFailedException("publish failed: " + ex.getCause(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new PublishFailedException("interrupted while waiting for confirm", ex);
        }
    }

    public static class PublishFailedException extends Exception {
        public PublishFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
