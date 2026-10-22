package com.grandvista.platform.messaging;

import java.util.Locale;

/**
 * Names shared by every event producer and consumer (see {@code contracts/README.md}).
 * <p>
 * All domain events go to one durable topic exchange. The routing key is derived from the event type,
 * e.g. {@code PAYMENT_SUCCEEDED → payment.succeeded}, {@code HOLD_EXPIRED → booking.hold-expired}.
 * Each consumer owns a durable queue whose rejected messages are dead-lettered to {@code <queue>.dlq}.
 */
public final class EventTopology {

    public static final String EXCHANGE = "grandvista.events";
    public static final String DEAD_LETTER_EXCHANGE = "grandvista.events.dlx";

    public static final String PAYMENT_SUCCEEDED = "PAYMENT_SUCCEEDED";
    public static final String PAYMENT_FAILED = "PAYMENT_FAILED";
    public static final String BOOKING_CREATED = "BOOKING_CREATED";
    public static final String BOOKING_CONFIRMED = "BOOKING_CONFIRMED";
    public static final String BOOKING_CANCELLED = "BOOKING_CANCELLED";
    public static final String HOLD_EXPIRED = "HOLD_EXPIRED";

    /** Message header carrying the correlation id across the broker. */
    public static final String CORRELATION_HEADER = "X-Correlation-Id";

    private EventTopology() {
    }

    public static String routingKey(String eventType) {
        return switch (eventType) {
            case HOLD_EXPIRED -> "booking.hold-expired";
            default -> {
                int underscore = eventType.indexOf('_');
                if (underscore < 0) {
                    throw new IllegalArgumentException("Unknown event type " + eventType);
                }
                yield eventType.substring(0, underscore).toLowerCase(Locale.ROOT) + "."
                        + eventType.substring(underscore + 1).toLowerCase(Locale.ROOT).replace('_', '-');
            }
        };
    }

    public static String deadLetterQueue(String queue) {
        return queue + ".dlq";
    }
}
