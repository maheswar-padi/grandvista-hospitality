package com.grandvista.room.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A room reserved for one booking's stay; {@code bookingId} is unique and acts as the idempotency key. */
@Entity
@Table(name = "inventory_allocations")
public class InventoryAllocation {

    @Id
    private UUID id;

    @Column(name = "booking_id", nullable = false, unique = true)
    private UUID bookingId;

    @Column(name = "room_id", nullable = false)
    private UUID roomId;

    @Column(name = "check_in", nullable = false)
    private LocalDate checkIn;

    @Column(name = "check_out", nullable = false)
    private LocalDate checkOut;

    @Column(nullable = false)
    private short guests;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AllocationStatus status;

    @Column(name = "hold_expires_at")
    private Instant holdExpiresAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "release_reason")
    private String releaseReason;

    /** JSON snapshot of the quote calculated when the hold was created. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "price_quote", nullable = false, columnDefinition = "jsonb")
    private String priceQuote;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected InventoryAllocation() {
    }

    public static InventoryAllocation hold(UUID bookingId, UUID roomId, LocalDate checkIn, LocalDate checkOut,
            short guests, Instant holdExpiresAt, String priceQuoteJson, Instant now) {
        InventoryAllocation a = new InventoryAllocation();
        a.id = UUID.randomUUID();
        a.bookingId = bookingId;
        a.roomId = roomId;
        a.checkIn = checkIn;
        a.checkOut = checkOut;
        a.guests = guests;
        a.status = AllocationStatus.HELD;
        a.holdExpiresAt = holdExpiresAt;
        a.priceQuote = priceQuoteJson;
        a.createdAt = now;
        a.updatedAt = now;
        return a;
    }

    public boolean isHoldExpired(Instant now) {
        return status == AllocationStatus.HELD && !holdExpiresAt.isAfter(now);
    }

    public boolean matches(UUID roomId, LocalDate checkIn, LocalDate checkOut) {
        return this.roomId.equals(roomId) && this.checkIn.equals(checkIn) && this.checkOut.equals(checkOut);
    }

    /** HELD or EXPIRED (re-acquire) → CONFIRMED. The exclusion constraint rejects a re-acquire that now overlaps. */
    public void confirm(Instant now) {
        if (status == AllocationStatus.RELEASED) {
            throw new IllegalStateException("A released allocation cannot be confirmed");
        }
        status = AllocationStatus.CONFIRMED;
        confirmedAt = now;
        holdExpiresAt = null;
        updatedAt = now;
    }

    public void release(String reason, Instant now) {
        status = AllocationStatus.RELEASED;
        releasedAt = now;
        releaseReason = reason;
        updatedAt = now;
    }

    public void expire(Instant now) {
        if (status != AllocationStatus.HELD) {
            throw new IllegalStateException("Only held allocations expire");
        }
        status = AllocationStatus.EXPIRED;
        releasedAt = now;
        releaseReason = "HOLD_EXPIRED";
        updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public UUID getRoomId() {
        return roomId;
    }

    public LocalDate getCheckIn() {
        return checkIn;
    }

    public LocalDate getCheckOut() {
        return checkOut;
    }

    public short getGuests() {
        return guests;
    }

    public AllocationStatus getStatus() {
        return status;
    }

    public Instant getHoldExpiresAt() {
        return holdExpiresAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public String getReleaseReason() {
        return releaseReason;
    }

    public String getPriceQuote() {
        return priceQuote;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
