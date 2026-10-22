package com.grandvista.room.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "pricing_demand_tiers")
public class PricingDemandTier {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "policy_id")
    private PricingPolicy policy;

    @Column(name = "min_occupancy", nullable = false, precision = 5, scale = 4)
    private BigDecimal minOccupancy;

    @Column(nullable = false, precision = 6, scale = 4)
    private BigDecimal multiplier;

    protected PricingDemandTier() {
    }

    PricingDemandTier(PricingPolicy policy, BigDecimal minOccupancy, BigDecimal multiplier) {
        this.id = UUID.randomUUID();
        this.policy = policy;
        this.minOccupancy = minOccupancy;
        this.multiplier = multiplier;
    }

    public BigDecimal getMinOccupancy() {
        return minOccupancy;
    }

    public BigDecimal getMultiplier() {
        return multiplier;
    }
}
