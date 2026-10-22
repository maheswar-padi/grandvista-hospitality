package com.grandvista.booking.service;

import com.grandvista.platform.security.AuthenticatedUser;

final class Actors {

    static final String RECOVERY = "system:recovery";
    static final String PAYMENT_EVENTS = "service:payment-service";

    private Actors() {
    }

    static String of(AuthenticatedUser user) {
        return user.id() != null ? "user:" + user.id() : user.subject();
    }
}
