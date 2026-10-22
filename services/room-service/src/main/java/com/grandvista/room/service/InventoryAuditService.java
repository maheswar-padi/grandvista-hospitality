package com.grandvista.room.service;

import com.grandvista.platform.security.AuthenticatedUser;
import com.grandvista.room.domain.InventoryEvent;
import com.grandvista.room.repository.InventoryEventRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Writes the inventory audit trail inside the caller's transaction. */
@Service
public class InventoryAuditService {

    private final InventoryEventRepository events;
    private final Clock clock;

    public InventoryAuditService(InventoryEventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(InventoryEvent.Type type, UUID roomId, UUID allocationId, UUID bookingId, String detail) {
        events.save(new InventoryEvent(roomId, allocationId, bookingId, type, currentActor(), detail, clock.instant()));
    }

    /** "user:&lt;id&gt;", "service:&lt;name&gt;" or "system" for scheduled jobs. */
    static String currentActor() {
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
