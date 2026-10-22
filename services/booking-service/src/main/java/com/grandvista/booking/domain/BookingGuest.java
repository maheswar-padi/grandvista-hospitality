package com.grandvista.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "booking_guests")
public class BookingGuest {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id")
    private Booking booking;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    private String email;

    private String phone;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    protected BookingGuest() {
    }

    BookingGuest(Booking booking, String fullName, String email, String phone, boolean primary) {
        this.id = UUID.randomUUID();
        this.booking = booking;
        this.fullName = fullName;
        this.email = email;
        this.phone = phone;
        this.primary = primary;
    }

    public String getFullName() {
        return fullName;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public boolean isPrimary() {
        return primary;
    }
}
