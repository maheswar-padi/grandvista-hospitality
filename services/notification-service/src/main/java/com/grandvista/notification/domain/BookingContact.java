package com.grandvista.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Local read model built from booking events (event-carried state transfer): what this service needs to address
 * an e-mail about a booking, so payment events (which carry no contact data) can be turned into e-mails without
 * calling the booking-service.
 */
@Entity
@Table(name = "booking_contacts")
public class BookingContact {

    @Id
    @Column(name = "booking_id")
    private UUID bookingId;

    @Column(nullable = false)
    private String reference;

    @Column(name = "customer_email", nullable = false)
    private String customerEmail;

    @Column(name = "guest_name")
    private String guestName;

    @Column(name = "room_name")
    private String roomName;

    @Column(name = "room_type_name")
    private String roomTypeName;

    @Column(name = "check_in")
    private LocalDate checkIn;

    @Column(name = "check_out")
    private LocalDate checkOut;

    private Integer nights;

    private Integer guests;

    @Column(name = "total_amount")
    private BigDecimal totalAmount;

    private String currency;

    @Column(name = "last_event_at", nullable = false)
    private Instant lastEventAt;

    protected BookingContact() {
    }

    public BookingContact(UUID bookingId) {
        this.bookingId = bookingId;
    }

    /** Applies the event's data unless a newer event was already applied (events may arrive out of order). */
    public boolean apply(String reference, String customerEmail, String guestName, String roomName, String roomTypeName,
            LocalDate checkIn, LocalDate checkOut, Integer nights, Integer guests, BigDecimal totalAmount,
            String currency, Instant occurredAt) {
        if (lastEventAt != null && occurredAt != null && occurredAt.isBefore(lastEventAt)) {
            return false;
        }
        this.reference = reference;
        this.customerEmail = customerEmail;
        this.guestName = guestName;
        this.roomName = roomName;
        this.roomTypeName = roomTypeName;
        this.checkIn = checkIn;
        this.checkOut = checkOut;
        this.nights = nights;
        this.guests = guests;
        this.totalAmount = totalAmount;
        this.currency = currency;
        this.lastEventAt = occurredAt == null ? Instant.now() : occurredAt;
        return true;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public String getReference() {
        return reference;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public String getGuestName() {
        return guestName;
    }

    public String getRoomName() {
        return roomName;
    }

    public String getRoomTypeName() {
        return roomTypeName;
    }

    public LocalDate getCheckIn() {
        return checkIn;
    }

    public LocalDate getCheckOut() {
        return checkOut;
    }

    public Integer getNights() {
        return nights;
    }

    public Integer getGuests() {
        return guests;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getCurrency() {
        return currency;
    }
}
