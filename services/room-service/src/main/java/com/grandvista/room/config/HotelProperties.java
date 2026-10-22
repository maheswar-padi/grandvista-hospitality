package com.grandvista.room.config;

import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Hotel-wide operating rules.
 *
 * @param zoneId          the hotel's time zone; all stay dates are calendar dates in this zone
 * @param maxNights       longest stay that can be booked online
 * @param maxAdvanceDays  how far ahead check-in may be
 * @param holdTtl         how long a payment hold keeps a room before it is released automatically
 * @param quoteValidity   informational validity shown with quotes
 */
@ConfigurationProperties("gv.hotel")
public record HotelProperties(ZoneId zoneId, int maxNights, int maxAdvanceDays, Duration holdTtl, Duration quoteValidity) {

    public HotelProperties {
        zoneId = zoneId == null ? ZoneId.of("Asia/Kolkata") : zoneId;
        maxNights = maxNights <= 0 ? 30 : maxNights;
        maxAdvanceDays = maxAdvanceDays <= 0 ? 365 : maxAdvanceDays;
        holdTtl = holdTtl == null ? Duration.ofMinutes(15) : holdTtl;
        quoteValidity = quoteValidity == null ? Duration.ofMinutes(15) : quoteValidity;
    }
}
