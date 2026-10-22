package com.grandvista.auth.service;

import com.grandvista.auth.domain.AuthAuditEvent;
import com.grandvista.auth.repository.AuthAuditEventRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {

    private final AuthAuditEventRepository repository;
    private final Clock clock;

    public AuditService(AuthAuditEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** Joins the caller's transaction so the audit record commits (or rolls back) with the change. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void record(UUID userId, AuthAuditEvent.Type type, String detail) {
        repository.save(new AuthAuditEvent(userId, type, detail, clock.instant()));
    }
}
