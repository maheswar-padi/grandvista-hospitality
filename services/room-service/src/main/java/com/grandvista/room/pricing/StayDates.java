package com.grandvista.room.pricing;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * A stay expressed in hotel-local dates as the half-open interval {@code [checkIn, checkOut)}: the guest
 * occupies the room on the nights of {@code checkIn ... checkOut-1} and the check-out day is free.
 */
public record StayDates(LocalDate checkIn, LocalDate checkOut) {

    public StayDates {
        Objects.requireNonNull(checkIn, "checkIn");
        Objects.requireNonNull(checkOut, "checkOut");
        if (!checkOut.isAfter(checkIn)) {
            throw new IllegalArgumentException("checkOut must be after checkIn");
        }
    }

    public int nights() {
        return (int) ChronoUnit.DAYS.between(checkIn, checkOut);
    }

    /** The dates of each night stayed (excludes the check-out date). */
    public List<LocalDate> nightDates() {
        return checkIn.datesUntil(checkOut).toList();
    }

    /** Half-open overlap: back-to-back stays (one checks out the day the other checks in) do not overlap. */
    public boolean overlaps(StayDates other) {
        return checkIn.isBefore(other.checkOut) && other.checkIn.isBefore(checkOut);
    }
}
