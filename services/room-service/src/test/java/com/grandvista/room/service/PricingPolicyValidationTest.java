package com.grandvista.room.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grandvista.platform.error.ApiException;
import com.grandvista.room.web.dto.PricingDtos.DemandTierDto;
import com.grandvista.room.web.dto.PricingDtos.PolicyRequest;
import com.grandvista.room.web.dto.PricingDtos.SeasonDto;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PricingPolicyValidationTest {

    private static final LocalDate D = LocalDate.of(2026, 12, 1);

    private static PolicyRequest request(List<SeasonDto> seasons, List<DemandTierDto> tiers, String min, String max) {
        return new PolicyRequest(Set.of(DayOfWeek.SATURDAY), BigDecimal.ONE, new BigDecimal(min), new BigDecimal(max),
                BigDecimal.ZERO, BigDecimal.ZERO, seasons, tiers, null);
    }

    private static final List<DemandTierDto> BASE = List.of(new DemandTierDto(BigDecimal.ZERO, BigDecimal.ONE));

    @Test
    void acceptsValidPolicy() {
        var seasons = List.of(new SeasonDto("A", D, D.plusDays(5), BigDecimal.ONE),
                new SeasonDto("B", D.plusDays(6), D.plusDays(9), BigDecimal.ONE));
        assertThatCode(() -> PricingPolicyService.validate(request(seasons, BASE, "0.8", "1.5"))).doesNotThrowAnyException();
    }

    @Test
    void rejectsOverlappingSeasonsMissingBaseTierDuplicateTiersAndInvertedLimits() {
        var overlapping = List.of(new SeasonDto("A", D, D.plusDays(5), BigDecimal.ONE),
                new SeasonDto("B", D.plusDays(5), D.plusDays(9), BigDecimal.ONE));
        assertThatThrownBy(() -> PricingPolicyService.validate(request(overlapping, BASE, "0.8", "1.5")))
                .isInstanceOf(ApiException.class).hasMessageContaining("overlap");
        assertThatThrownBy(() -> PricingPolicyService.validate(request(List.of(),
                List.of(new DemandTierDto(new BigDecimal("0.5"), BigDecimal.ONE)), "0.8", "1.5")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PricingPolicyService.validate(request(List.of(),
                List.of(new DemandTierDto(BigDecimal.ZERO, BigDecimal.ONE), new DemandTierDto(new BigDecimal("0.00"), BigDecimal.TEN)),
                "0.8", "1.5"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PricingPolicyService.validate(request(List.of(), BASE, "0.9", "0.8")))
                .isInstanceOf(ApiException.class);
    }
}
