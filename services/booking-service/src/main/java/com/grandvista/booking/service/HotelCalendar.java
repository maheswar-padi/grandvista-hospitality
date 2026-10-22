package com.grandvista.booking.service;

import com.grandvista.booking.config.BookingProperties;
import com.grandvista.platform.error.ApiException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/** Hotel-local dates. Room-service re-validates authoritatively; these checks give fast, clear errors. */
@Component
public class HotelCalendar {

    private final BookingProperties properties;
    private final Clock clock;

    public HotelCalendar(BookingProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(properties.hotelZone()));
    }

    public void validateStay(LocalDate checkIn, LocalDate checkOut) {
        if (!checkOut.isAfter(checkIn)) {
            throw ApiException.badRequest("INVALID_DATE_RANGE", "Check-out must be after check-in.");
        }
        if (checkIn.isBefore(today())) {
            throw ApiException.badRequest("CHECK_IN_IN_PAST", "Check-in cannot be in the past.");
        }
        if (ChronoUnit.DAYS.between(checkIn, checkOut) > properties.maxNights()) {
            throw ApiException.badRequest("STAY_TOO_LONG", "Stays are limited to " + properties.maxNights() + " nights.");
        }
    }
}
