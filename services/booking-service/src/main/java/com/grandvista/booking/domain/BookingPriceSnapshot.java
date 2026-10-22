package com.grandvista.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Immutable copy of the price the customer agreed to. Later pricing changes never alter it. */
@Entity
@Immutable
@Table(name = "booking_price_snapshots")
public class BookingPriceSnapshot {

    @Id
    @Column(name = "booking_id")
    private UUID bookingId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id")
    private Booking booking;

    @Column(name = "pricing_policy_version", nullable = false)
    private int pricingPolicyVersion;

    @Column(nullable = false)
    private short nights;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "service_fee", nullable = false, precision = 12, scale = 2)
    private BigDecimal serviceFee;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal taxes;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal total;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "quoted_at", nullable = false)
    private Instant quotedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "nightly_rates", nullable = false, columnDefinition = "jsonb")
    private String nightlyRates;

    protected BookingPriceSnapshot() {
    }

    public BookingPriceSnapshot(Booking booking, int pricingPolicyVersion, int nights, BigDecimal subtotal,
            BigDecimal serviceFee, BigDecimal taxes, BigDecimal total, String currency, Instant quotedAt,
            String nightlyRatesJson) {
        this.booking = booking;
        this.pricingPolicyVersion = pricingPolicyVersion;
        this.nights = (short) nights;
        this.subtotal = subtotal;
        this.serviceFee = serviceFee;
        this.taxes = taxes;
        this.total = total;
        this.currency = currency;
        this.quotedAt = quotedAt;
        this.nightlyRates = nightlyRatesJson;
    }

    public int getPricingPolicyVersion() {
        return pricingPolicyVersion;
    }

    public int getNights() {
        return nights;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public BigDecimal getServiceFee() {
        return serviceFee;
    }

    public BigDecimal getTaxes() {
        return taxes;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getQuotedAt() {
        return quotedAt;
    }

    public String getNightlyRates() {
        return nightlyRates;
    }
}
