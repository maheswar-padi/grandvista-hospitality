package com.grandvista.payment.domain;

public class IllegalPaymentTransitionException extends RuntimeException {

    private final PaymentStatus from;
    private final PaymentStatus to;

    public IllegalPaymentTransitionException(PaymentStatus from, PaymentStatus to) {
        super("Illegal payment transition " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    public PaymentStatus getFrom() {
        return from;
    }

    public PaymentStatus getTo() {
        return to;
    }
}
