package com.grandvista.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "auth_audit_events")
public class AuthAuditEvent {

    public enum Type {
        REGISTERED, LOGIN_SUCCEEDED, LOGIN_FAILED, ACCOUNT_LOCKED, LOGOUT, REFRESH_REUSE_DETECTED,
        ROLES_CHANGED, ACCOUNT_ENABLED, ACCOUNT_DISABLED, ADMIN_BOOTSTRAPPED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    private String detail;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected AuthAuditEvent() {
    }

    public AuthAuditEvent(UUID userId, Type type, String detail, Instant occurredAt) {
        this.userId = userId;
        this.eventType = type.name();
        this.detail = detail;
        this.occurredAt = occurredAt;
    }

    public String getEventType() {
        return eventType;
    }

    public UUID getUserId() {
        return userId;
    }
}
