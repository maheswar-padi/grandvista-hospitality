package com.grandvista.room.domain;

import com.grandvista.room.pricing.PricingPolicySnapshot;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** One immutable version of the pricing rules. Publishing creates a new version and archives the old one. */
@Entity
@Table(name = "pricing_policies")
public class PricingPolicy {

    public enum Status { ACTIVE, ARCHIVED }

    @Id
    private UUID id;

    @Column(name = "property_id", nullable = false)
    private UUID propertyId;

    /** Version number within the property's policy history. */
    @Column(nullable = false)
    private int version;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "weekend_days", nullable = false)
    private String weekendDays;

    @Column(name = "weekend_multiplier", nullable = false, precision = 6, scale = 4)
    private BigDecimal weekendMultiplier;

    @Column(name = "min_price_factor", nullable = false, precision = 6, scale = 4)
    private BigDecimal minPriceFactor;

    @Column(name = "max_price_factor", nullable = false, precision = 6, scale = 4)
    private BigDecimal maxPriceFactor;

    @Column(name = "service_fee_rate", nullable = false, precision = 6, scale = 4)
    private BigDecimal serviceFeeRate;

    @Column(name = "tax_rate", nullable = false, precision = 6, scale = 4)
    private BigDecimal taxRate;

    private String notes;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @org.hibernate.annotations.Fetch(org.hibernate.annotations.FetchMode.SUBSELECT)
    @OneToMany(mappedBy = "policy", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("startDate ASC")
    private List<PricingSeason> seasons = new ArrayList<>();

    @org.hibernate.annotations.Fetch(org.hibernate.annotations.FetchMode.SUBSELECT)
    @OneToMany(mappedBy = "policy", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("minOccupancy ASC")
    private List<PricingDemandTier> demandTiers = new ArrayList<>();

    protected PricingPolicy() {
    }

    public PricingPolicy(UUID propertyId, int version, Set<DayOfWeek> weekendDays, BigDecimal weekendMultiplier,
            BigDecimal minPriceFactor, BigDecimal maxPriceFactor, BigDecimal serviceFeeRate, BigDecimal taxRate,
            String notes, String createdBy, Instant now) {
        this.id = UUID.randomUUID();
        this.propertyId = propertyId;
        this.version = version;
        this.status = Status.ACTIVE;
        this.weekendDays = weekendDays.stream().sorted().map(DayOfWeek::name).collect(Collectors.joining(","));
        this.weekendMultiplier = weekendMultiplier;
        this.minPriceFactor = minPriceFactor;
        this.maxPriceFactor = maxPriceFactor;
        this.serviceFeeRate = serviceFeeRate;
        this.taxRate = taxRate;
        this.notes = notes;
        this.createdBy = createdBy;
        this.createdAt = now;
    }

    public void addSeason(String name, java.time.LocalDate start, java.time.LocalDate end, BigDecimal multiplier) {
        seasons.add(new PricingSeason(this, name, start, end, multiplier));
    }

    public void addDemandTier(BigDecimal minOccupancy, BigDecimal multiplier) {
        demandTiers.add(new PricingDemandTier(this, minOccupancy, multiplier));
    }

    public void archive() {
        this.status = Status.ARCHIVED;
    }

    public Set<DayOfWeek> weekendDaySet() {
        if (weekendDays == null || weekendDays.isBlank()) {
            return EnumSet.noneOf(DayOfWeek.class);
        }
        Collection<DayOfWeek> days = Arrays.stream(weekendDays.split(",")).map(String::trim).map(DayOfWeek::valueOf).toList();
        return EnumSet.copyOf(days);
    }

    /** A copy of these rules as version {@code version} of another property's policy history. */
    public PricingPolicy copyFor(UUID targetPropertyId, int targetVersion, String notes, String actor, Instant now) {
        PricingPolicy copy = new PricingPolicy(targetPropertyId, targetVersion, weekendDaySet(), weekendMultiplier,
                minPriceFactor, maxPriceFactor, serviceFeeRate, taxRate, notes, actor, now);
        seasons.forEach(s -> copy.addSeason(s.getName(), s.getStartDate(), s.getEndDate(), s.getMultiplier()));
        demandTiers.forEach(t -> copy.addDemandTier(t.getMinOccupancy(), t.getMultiplier()));
        return copy;
    }

    public PricingPolicySnapshot toSnapshot() {
        return new PricingPolicySnapshot(version, weekendDaySet(), weekendMultiplier, minPriceFactor, maxPriceFactor,
                serviceFeeRate, taxRate,
                seasons.stream().map(s -> new PricingPolicySnapshot.Season(s.getName(), s.getStartDate(), s.getEndDate(),
                        s.getMultiplier())).toList(),
                demandTiers.stream().map(t -> new PricingPolicySnapshot.DemandTier(t.getMinOccupancy(), t.getMultiplier()))
                        .toList());
    }

    public UUID getId() {
        return id;
    }

    public UUID getPropertyId() {
        return propertyId;
    }

    public int getVersion() {
        return version;
    }

    public Status getStatus() {
        return status;
    }

    public BigDecimal getWeekendMultiplier() {
        return weekendMultiplier;
    }

    public BigDecimal getMinPriceFactor() {
        return minPriceFactor;
    }

    public BigDecimal getMaxPriceFactor() {
        return maxPriceFactor;
    }

    public BigDecimal getServiceFeeRate() {
        return serviceFeeRate;
    }

    public BigDecimal getTaxRate() {
        return taxRate;
    }

    public String getNotes() {
        return notes;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<PricingSeason> getSeasons() {
        return List.copyOf(seasons);
    }

    public List<PricingDemandTier> getDemandTiers() {
        return List.copyOf(demandTiers);
    }
}
