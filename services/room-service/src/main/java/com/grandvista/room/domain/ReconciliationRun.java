package com.grandvista.room.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One stock-reconciliation run for one property (see {@code InventoryReconciliationService}). */
@Entity
@Table(name = "inventory_reconciliation_runs")
public class ReconciliationRun {

    public enum Trigger { SCHEDULED, MANUAL }

    public enum Status { COMPLETED, FAILED }

    @Id
    private UUID id;

    @Column(name = "property_id", nullable = false)
    private UUID propertyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false)
    private Trigger trigger;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at", nullable = false)
    private Instant finishedAt;

    @Column(name = "allocations_checked", nullable = false)
    private int allocationsChecked;

    @Column(name = "bookings_checked", nullable = false)
    private int bookingsChecked;

    @Column(name = "finding_count", nullable = false)
    private int findingCount;

    @Column(name = "released_count", nullable = false)
    private int releasedCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String findings;

    private String error;

    @Column(nullable = false)
    private String actor;

    protected ReconciliationRun() {
    }

    public static ReconciliationRun completed(UUID propertyId, Trigger trigger, String actor, Instant startedAt,
            Instant finishedAt, int allocationsChecked, int bookingsChecked, int findingCount, int releasedCount,
            String findingsJson) {
        ReconciliationRun r = base(propertyId, trigger, actor, startedAt, finishedAt);
        r.status = Status.COMPLETED;
        r.allocationsChecked = allocationsChecked;
        r.bookingsChecked = bookingsChecked;
        r.findingCount = findingCount;
        r.releasedCount = releasedCount;
        r.findings = findingsJson;
        return r;
    }

    public static ReconciliationRun failed(UUID propertyId, Trigger trigger, String actor, Instant startedAt,
            Instant finishedAt, String error) {
        ReconciliationRun r = base(propertyId, trigger, actor, startedAt, finishedAt);
        r.status = Status.FAILED;
        r.findings = "[]";
        r.error = error.length() > 300 ? error.substring(0, 300) : error;
        return r;
    }

    private static ReconciliationRun base(UUID propertyId, Trigger trigger, String actor, Instant startedAt,
            Instant finishedAt) {
        ReconciliationRun r = new ReconciliationRun();
        r.id = UUID.randomUUID();
        r.propertyId = propertyId;
        r.trigger = trigger;
        r.actor = actor;
        r.startedAt = startedAt;
        r.finishedAt = finishedAt;
        return r;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPropertyId() {
        return propertyId;
    }

    public Trigger getTrigger() {
        return trigger;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public int getAllocationsChecked() {
        return allocationsChecked;
    }

    public int getBookingsChecked() {
        return bookingsChecked;
    }

    public int getFindingCount() {
        return findingCount;
    }

    public int getReleasedCount() {
        return releasedCount;
    }

    public String getFindings() {
        return findings;
    }

    public String getError() {
        return error;
    }

    public String getActor() {
        return actor;
    }
}
