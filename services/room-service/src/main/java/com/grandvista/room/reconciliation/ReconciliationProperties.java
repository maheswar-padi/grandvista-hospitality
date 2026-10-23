package com.grandvista.room.reconciliation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param enabled         run the scheduled job (the admin endpoint works either way)
 * @param interval        delay between scheduled runs
 * @param initialDelay    delay before the first scheduled run after start-up
 * @param grace           records changed more recently than this are skipped (saga still in flight)
 * @param bookingServiceUrl base URL of the booking-service (resolved through Eureka)
 * @param httpTimeout     read timeout for the booking-service lookups
 */
@ConfigurationProperties("gv.inventory.reconciliation")
public record ReconciliationProperties(boolean enabled, Duration interval, Duration initialDelay, Duration grace,
        String bookingServiceUrl, Duration httpTimeout) {

    public ReconciliationProperties {
        interval = interval == null ? Duration.ofMinutes(10) : interval;
        initialDelay = initialDelay == null ? Duration.ofMinutes(2) : initialDelay;
        grace = grace == null ? Duration.ofMinutes(2) : grace;
        bookingServiceUrl = bookingServiceUrl == null || bookingServiceUrl.isBlank() ? "http://booking-service" : bookingServiceUrl;
        httpTimeout = httpTimeout == null ? Duration.ofSeconds(5) : httpTimeout;
    }
}
