package com.grandvista.payment.service;

import com.grandvista.payment.domain.PaymentEvent;
import com.grandvista.payment.domain.PaymentStatus;
import com.grandvista.payment.repository.PaymentEventRepository;
import com.grandvista.platform.security.AuthenticatedUser;
import java.time.Clock;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PaymentAudit {

    private final PaymentEventRepository events;
    private final Clock clock;

    public PaymentAudit(PaymentEventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID paymentId, String type, PaymentStatus from, PaymentStatus to, String detail) {
        events.save(new PaymentEvent(paymentId, type, from, to, actor(), detail, clock.instant()));
    }

    static String actor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return "system";
        }
        try {
            AuthenticatedUser user = AuthenticatedUser.from(auth);
            return user.id() != null ? "user:" + user.id() : user.subject();
        } catch (RuntimeException ex) {
            return "system";
        }
    }
}
