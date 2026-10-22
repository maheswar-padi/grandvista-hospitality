package com.grandvista.payment.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gv.payment")
public record PaymentProperties(
        /** Shared secret used to verify provider webhooks (HMAC-SHA256). */
        String webhookSecret,
        Duration webhookTolerance,
        Outbox outbox,
        Reconciliation reconciliation,
        Simulator simulator) {

    public PaymentProperties {
        webhookTolerance = webhookTolerance == null ? Duration.ofMinutes(5) : webhookTolerance;
        outbox = outbox == null ? new Outbox(null, null, 0, null, null) : outbox;
        reconciliation = reconciliation == null ? new Reconciliation(null) : reconciliation;
        simulator = simulator == null ? new Simulator(true, null, null) : simulator;
    }

    @Override
    public String toString() {
        return "PaymentProperties[webhookSecret=***, outbox=" + outbox + ", simulator=" + simulator + "]";
    }

    /**
     * Delivery of payment events. {@code RABBIT} (default) publishes to the {@code grandvista.events} exchange,
     * where the booking- and notification-services consume them; {@code HTTP} posts directly to the
     * booking-service (no broker required).
     */
    public record Outbox(Transport transport, String bookingServiceUrl, int maxAttempts, Duration maxBackoff,
                         Duration httpTimeout) {
        public enum Transport { RABBIT, HTTP }

        public Outbox {
            transport = transport == null ? Transport.RABBIT : transport;
            bookingServiceUrl = bookingServiceUrl == null ? "http://booking-service" : bookingServiceUrl;
            maxAttempts = maxAttempts <= 0 ? 25 : maxAttempts;
            maxBackoff = maxBackoff == null ? Duration.ofMinutes(5) : maxBackoff;
            httpTimeout = httpTimeout == null ? Duration.ofSeconds(5) : httpTimeout;
        }
    }

    /** Payments PENDING for longer than {@code pendingThreshold} are re-checked with the provider. */
    public record Reconciliation(Duration pendingThreshold) {
        public Reconciliation {
            pendingThreshold = pendingThreshold == null ? Duration.ofSeconds(45) : pendingThreshold;
        }
    }

    /** SIMULATED provider used for development, demos and tests. Moves no real money. */
    public record Simulator(boolean enabled, String webhookUrl, Duration webhookDelay) {
        public Simulator {
            webhookUrl = webhookUrl == null ? "http://localhost:8084/api/v1/payments/webhooks/simulated" : webhookUrl;
            webhookDelay = webhookDelay == null ? Duration.ofSeconds(3) : webhookDelay;
        }
    }
}
