package com.grandvista.booking.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrimaryKeyJoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "bookings")
public class Booking {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String reference;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "customer_email", nullable = false)
    private String customerEmail;

    @Column(name = "room_id", nullable = false)
    private UUID roomId;

    @Column(name = "room_number")
    private String roomNumber;

    @Column(name = "room_name")
    private String roomName;

    @Column(name = "room_type_name")
    private String roomTypeName;

    /** Property (hotel) of the room, copied from the room-service hold; null for pre-portfolio bookings. */
    @Column(name = "property_id")
    private UUID propertyId;

    @Column(name = "property_name")
    private String propertyName;

    @Column(name = "check_in", nullable = false)
    private LocalDate checkIn;

    @Column(name = "check_out", nullable = false)
    private LocalDate checkOut;

    @Column(nullable = false)
    private short nights;

    @Column(nullable = false)
    private short guests;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false)
    private String requestFingerprint;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "hold_expires_at")
    private Instant holdExpiresAt;

    @Column(name = "total_amount", precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(length = 3)
    private String currency;

    @Column(name = "refund_amount", precision = 12, scale = 2)
    private BigDecimal refundAmount;

    @Column(name = "status_reason")
    private String statusReason;

    @Column(name = "special_requests")
    private String specialRequests;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<BookingGuest> guestsList = new ArrayList<>();

    @OneToOne(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @PrimaryKeyJoinColumn
    private BookingPriceSnapshot priceSnapshot;

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("occurredAt ASC, id ASC")
    private List<BookingStatusHistory> history = new ArrayList<>();

    protected Booking() {
    }

    public Booking(String reference, UUID customerId, String customerEmail, UUID roomId, LocalDate checkIn,
            LocalDate checkOut, int guests, String idempotencyKey, String requestFingerprint, String specialRequests,
            Instant now) {
        this.id = UUID.randomUUID();
        this.reference = reference;
        this.customerId = customerId;
        this.customerEmail = customerEmail;
        this.roomId = roomId;
        this.checkIn = checkIn;
        this.checkOut = checkOut;
        this.nights = (short) ChronoUnit.DAYS.between(checkIn, checkOut);
        this.guests = (short) guests;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.specialRequests = specialRequests;
        this.status = BookingStatus.CREATED;
        this.createdAt = now;
        this.updatedAt = now;
        this.history.add(new BookingStatusHistory(this, null, BookingStatus.CREATED, "booking requested", "system", now));
    }

    /** The only way to change status: validates the transition and appends to the audit history. */
    public void transitionTo(BookingStatus target, String reason, String actor, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalBookingTransitionException(status, target);
        }
        history.add(new BookingStatusHistory(this, status, target, reason, actor, now));
        status = target;
        statusReason = reason;
        updatedAt = now;
        if (target == BookingStatus.CONFIRMED) {
            confirmedAt = now;
        } else if (target == BookingStatus.CANCELLED) {
            cancelledAt = now;
        }
    }

    public void addGuest(String fullName, String email, String phone, boolean primary) {
        guestsList.add(new BookingGuest(this, fullName, email, phone, primary));
    }

    /** Records the room and the authoritative price returned with the inventory hold. Done once. */
    public void attachHold(UUID propertyId, String propertyName, String roomNumber, String roomName, String roomTypeName,
            Instant holdExpiresAt, BookingPriceSnapshot snapshot, Instant now) {
        this.propertyId = propertyId;
        this.propertyName = propertyName;
        this.roomNumber = roomNumber;
        this.roomName = roomName;
        this.roomTypeName = roomTypeName;
        this.holdExpiresAt = holdExpiresAt;
        if (this.priceSnapshot == null) {
            this.priceSnapshot = snapshot;
            this.totalAmount = snapshot.getTotal();
            this.currency = snapshot.getCurrency();
        }
        this.updatedAt = now;
    }

    public void attachPayment(UUID paymentId, Instant now) {
        this.paymentId = paymentId;
        this.updatedAt = now;
    }

    public void setRefundAmount(BigDecimal refundAmount) {
        this.refundAmount = refundAmount;
    }

    public boolean isOwnedBy(UUID userId) {
        return customerId.equals(userId);
    }

    public UUID getId() {
        return id;
    }

    public String getReference() {
        return reference;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public UUID getRoomId() {
        return roomId;
    }

    public String getRoomNumber() {
        return roomNumber;
    }

    public String getRoomName() {
        return roomName;
    }

    public String getRoomTypeName() {
        return roomTypeName;
    }

    public UUID getPropertyId() {
        return propertyId;
    }

    public String getPropertyName() {
        return propertyName;
    }

    public LocalDate getCheckIn() {
        return checkIn;
    }

    public LocalDate getCheckOut() {
        return checkOut;
    }

    public int getNights() {
        return nights;
    }

    public int getGuests() {
        return guests;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public Instant getHoldExpiresAt() {
        return holdExpiresAt;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getRefundAmount() {
        return refundAmount;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public String getSpecialRequests() {
        return specialRequests;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<BookingGuest> getGuestsList() {
        return List.copyOf(guestsList);
    }

    public BookingPriceSnapshot getPriceSnapshot() {
        return priceSnapshot;
    }

    public List<BookingStatusHistory> getHistory() {
        return List.copyOf(history);
    }
}
