package com.grandvista.room.service;

import com.grandvista.platform.error.ApiException;
import com.grandvista.room.config.HotelProperties;
import com.grandvista.room.pricing.StayDates;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/** Validates requested stays against the hotel calendar (dates are interpreted in the hotel's zone). */
@Component
public class StayRules {

    private final HotelProperties hotel;
    private final Clock clock;

    public StayRules(HotelProperties hotel, Clock clock) {
        this.hotel = hotel;
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(hotel.zoneId()));
    }

    public StayDates validate(LocalDate checkIn, LocalDate checkOut) {
        if (checkIn == null || checkOut == null) {
            throw ApiException.badRequest("DATES_REQUIRED", "Both check-in and check-out dates are required.");
        }
        if (!checkOut.isAfter(checkIn)) {
            throw ApiException.badRequest("INVALID_DATE_RANGE", "Check-out must be after check-in.");
        }
        LocalDate today = today();
        if (checkIn.isBefore(today)) {
            throw ApiException.badRequest("CHECK_IN_IN_PAST", "Check-in cannot be in the past.");
        }
        if (checkIn.isAfter(today.plusDays(hotel.maxAdvanceDays()))) {
            throw ApiException.badRequest("BOOKING_WINDOW_EXCEEDED",
                    "Check-in can be at most " + hotel.maxAdvanceDays() + " days ahead.");
        }
        StayDates stay = new StayDates(checkIn, checkOut);
        if (stay.nights() > hotel.maxNights()) {
            throw ApiException.badRequest("STAY_TOO_LONG", "Stays are limited to " + hotel.maxNights() + " nights.");
        }
        return stay;
    }
}
