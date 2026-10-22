package com.grandvista.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Receipt of a verified provider callback; the unique (provider, event id) pair deduplicates deliveries. */
@Entity
@Table(name = "webhook_events")
public class WebhookEvent {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String provider;

    @Column(name = "provider_event_id", nullable = false)
    private String providerEventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "payload_sha256", nullable = false)
    private String payloadSha256;

    @Column(nullable = false)
    private String outcome;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected WebhookEvent() {
    }

    public WebhookEvent(String provider, String providerEventId, String eventType, String payloadSha256, String outcome,
            Instant receivedAt) {
        this.id = UUID.randomUUID();
        this.provider = provider;
        this.providerEventId = providerEventId;
        this.eventType = eventType;
        this.payloadSha256 = payloadSha256;
        this.outcome = outcome;
        this.receivedAt = receivedAt;
    }
}
