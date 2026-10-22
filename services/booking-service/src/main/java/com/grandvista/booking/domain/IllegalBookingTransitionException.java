package com.grandvista.booking.domain;

public class IllegalBookingTransitionException extends RuntimeException {

    private final BookingStatus from;
    private final BookingStatus to;

    public IllegalBookingTransitionException(BookingStatus from, BookingStatus to) {
        super("Illegal booking transition " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    public BookingStatus getFrom() {
        return from;
    }

    public BookingStatus getTo() {
        return to;
    }
}
