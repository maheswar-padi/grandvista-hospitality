package com.grandvista.payment.service;

import com.grandvista.payment.domain.OutboxEvent;

/** Transport used by {@link OutboxRelay} to deliver one outbox event (at-least-once). */
public interface OutboxPublisher {

    /**
     * @throws RetryableDeliveryException the event may succeed later (consumer or broker unavailable)
     * @throws RejectedDeliveryException  the consumer rejected the contract; retrying cannot succeed
     */
    void publish(OutboxEvent event) throws RetryableDeliveryException, RejectedDeliveryException;

    String name();

    class RetryableDeliveryException extends Exception {
        public RetryableDeliveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    class RejectedDeliveryException extends Exception {
        public RejectedDeliveryException(String message) {
            super(message);
        }
    }
}
