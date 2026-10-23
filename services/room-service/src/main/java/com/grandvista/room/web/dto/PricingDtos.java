package com.grandvista.room.web.dto;

import com.grandvista.room.domain.PricingPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class PricingDtos {

    private PricingDtos() {
    }

    public record SeasonDto(@NotBlank @Size(max = 80) String name, @NotNull LocalDate startDate, @NotNull LocalDate endDate,
                            @NotNull @DecimalMin("0.5") @DecimalMax("3.0") @Digits(integer = 1, fraction = 4)
                            BigDecimal multiplier) {
    }

    public record DemandTierDto(@NotNull @DecimalMin("0") @DecimalMax("1") @Digits(integer = 1, fraction = 4)
                                BigDecimal minOccupancy,
                                @NotNull @DecimalMin("0.5") @DecimalMax("3.0") @Digits(integer = 1, fraction = 4)
                                BigDecimal multiplier) {
    }

    public record PolicyRequest(
            @NotNull @Size(max = 7) Set<DayOfWeek> weekendDays,
            @NotNull @DecimalMin("0.5") @DecimalMax("3.0") @Digits(integer = 1, fraction = 4) BigDecimal weekendMultiplier,
            @NotNull @DecimalMin("0.1") @DecimalMax("1.0") @Digits(integer = 1, fraction = 4) BigDecimal minPriceFactor,
            @NotNull @DecimalMin("1.0") @DecimalMax("5.0") @Digits(integer = 1, fraction = 4) BigDecimal maxPriceFactor,
            @NotNull @DecimalMin("0") @DecimalMax("0.5") @Digits(integer = 1, fraction = 4) BigDecimal serviceFeeRate,
            @NotNull @DecimalMin("0") @DecimalMax("0.5") @Digits(integer = 1, fraction = 4) BigDecimal taxRate,
            @NotNull @Size(max = 20) List<@Valid SeasonDto> seasons,
            @NotEmpty @Size(max = 10) List<@Valid DemandTierDto> demandTiers,
            @Size(max = 500) String notes) {
    }

    public record PolicyResponse(UUID id, UUID propertyId, int version, String status, Set<DayOfWeek> weekendDays,
                                 BigDecimal weekendMultiplier, BigDecimal minPriceFactor, BigDecimal maxPriceFactor,
                                 BigDecimal serviceFeeRate, BigDecimal taxRate, List<SeasonDto> seasons,
                                 List<DemandTierDto> demandTiers, String notes, String createdBy, Instant createdAt) {
        public static PolicyResponse from(PricingPolicy p) {
            return new PolicyResponse(p.getId(), p.getPropertyId(), p.getVersion(), p.getStatus().name(), p.weekendDaySet(),
                    p.getWeekendMultiplier(), p.getMinPriceFactor(), p.getMaxPriceFactor(), p.getServiceFeeRate(),
                    p.getTaxRate(),
                    p.getSeasons().stream().map(s -> new SeasonDto(s.getName(), s.getStartDate(), s.getEndDate(),
                            s.getMultiplier())).toList(),
                    p.getDemandTiers().stream().map(t -> new DemandTierDto(t.getMinOccupancy(), t.getMultiplier())).toList(),
                    p.getNotes(), p.getCreatedBy(), p.getCreatedAt());
        }
    }
}
