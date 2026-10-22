package com.grandvista.payment.service;

import com.grandvista.payment.domain.OutboxEvent;
import com.grandvista.platform.messaging.ConfirmedRabbitPublisher;

/** Publishes payment events to the {@code grandvista.events} exchange and waits for the broker's confirm. */
public class RabbitOutboxPublisher implements OutboxPublisher {

    private final ConfirmedRabbitPublisher publisher;

    public RabbitOutboxPublisher(ConfirmedRabbitPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public String name() {
        return "RabbitMQ";
    }

    @Override
    public void publish(OutboxEvent event) throws RetryableDeliveryException {
        try {
            publisher.publish(event.getId().toString(), event.getEventType(), event.getPayload(), event.getCorrelationId());
        } catch (ConfirmedRabbitPublisher.PublishFailedException ex) {
            throw new RetryableDeliveryException(ex.getMessage(), ex);
        }
    }
}
