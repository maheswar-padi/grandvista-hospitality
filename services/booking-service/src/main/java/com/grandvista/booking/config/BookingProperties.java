package com.grandvista.booking.config;

import com.grandvista.booking.domain.CancellationPolicy;
import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gv.booking")
public record BookingProperties(
        ZoneId hotelZone,
        int maxNights,
        Cancellation cancellation,
        Recovery recovery,
        Downstream rooms,
        Downstream payments) {

    public BookingProperties {
        hotelZone = hotelZone == null ? ZoneId.of("Asia/Kolkata") : hotelZone;
        maxNights = maxNights <= 0 ? 30 : maxNights;
        cancellation = cancellation == null ? new Cancellation(7, 2, 50) : cancellation;
        recovery = recovery == null ? new Recovery(null, null) : recovery;
        rooms = rooms == null ? new Downstream("http://room-service", null, null) : rooms;
        payments = payments == null ? new Downstream("http://payment-service", null, null) : payments;
    }

    public record Cancellation(int fullRefundDays, int partialRefundDays, int partialRefundPercent) {
        public CancellationPolicy toPolicy() {
            return new CancellationPolicy(fullRefundDays, partialRefundDays, partialRefundPercent);
        }
    }

    /**
     * @param stuckThreshold   how long a booking may sit in CREATED/CANCELLATION_PENDING before recovery acts
     * @param expiryGrace      extra time after the hold expiry before an unpaid booking is expired
     */
    public record Recovery(Duration stuckThreshold, Duration expiryGrace) {
        public Recovery {
            stuckThreshold = stuckThreshold == null ? Duration.ofMinutes(2) : stuckThreshold;
            expiryGrace = expiryGrace == null ? Duration.ofSeconds(30) : expiryGrace;
        }
    }

    /** A downstream service: base URL (a Eureka service id) and explicit timeouts. */
    public record Downstream(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        public Downstream {
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
            readTimeout = readTimeout == null ? Duration.ofSeconds(5) : readTimeout;
        }
    }
}
