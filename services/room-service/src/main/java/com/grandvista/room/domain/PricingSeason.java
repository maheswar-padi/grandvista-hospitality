package com.grandvista.room.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "pricing_seasons")
public class PricingSeason {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "policy_id")
    private PricingPolicy policy;

    @Column(nullable = false)
    private String name;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(nullable = false, precision = 6, scale = 4)
    private BigDecimal multiplier;

    protected PricingSeason() {
    }

    PricingSeason(PricingPolicy policy, String name, LocalDate startDate, LocalDate endDate, BigDecimal multiplier) {
        this.id = UUID.randomUUID();
        this.policy = policy;
        this.name = name;
        this.startDate = startDate;
        this.endDate = endDate;
        this.multiplier = multiplier;
    }

    public String getName() {
        return name;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public BigDecimal getMultiplier() {
        return multiplier;
    }
}
