package com.grandvista.room.service;

import com.grandvista.platform.error.ApiException;
import com.grandvista.room.domain.InventoryEvent;
import com.grandvista.room.domain.PricingPolicy;
import com.grandvista.room.repository.PricingPolicyRepository;
import com.grandvista.room.web.dto.PricingDtos.DemandTierDto;
import com.grandvista.room.web.dto.PricingDtos.PolicyRequest;
import com.grandvista.room.web.dto.PricingDtos.SeasonDto;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes pricing rules as new immutable versions. Existing quotes and bookings keep the version they
 * were priced with; only new quotes use the newly published version.
 */
@Service
public class PricingPolicyService {

    private final PricingPolicyRepository policies;
    private final InventoryAuditService audit;
    private final Clock clock;

    public PricingPolicyService(PricingPolicyRepository policies, InventoryAuditService audit, Clock clock) {
        this.policies = policies;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PricingPolicy active(UUID propertyId) {
        return policies.findActive(propertyId)
                .orElseThrow(() -> ApiException.notFound("PRICING_POLICY_MISSING", "No active policy for this property."));
    }

    @Transactional(readOnly = true)
    public List<PricingPolicy> history(UUID propertyId) {
        return policies.findAllByPropertyIdOrderByVersionDesc(propertyId);
    }

    /** Publishes version N+1 of one property's policy; other properties are unaffected. */
    @Transactional
    public PricingPolicy publish(UUID propertyId, PolicyRequest request, String actor) {
        validate(request);
        // Lock the property's active row so two concurrent publishes cannot both become version N+1.
        policies.findActiveForUpdate(propertyId).ifPresent(current -> {
            current.archive();
            policies.saveAndFlush(current);
        });
        PricingPolicy policy = new PricingPolicy(propertyId, policies.maxVersion(propertyId) + 1, request.weekendDays(),
                request.weekendMultiplier(), request.minPriceFactor(), request.maxPriceFactor(), request.serviceFeeRate(),
                request.taxRate(), request.notes(), actor, clock.instant());
        request.seasons().forEach(s -> policy.addSeason(s.name().trim(), s.startDate(), s.endDate(), s.multiplier()));
        request.demandTiers().forEach(t -> policy.addDemandTier(t.minOccupancy(), t.multiplier()));
        policies.save(policy);
        audit.record(InventoryEvent.Type.PRICING_POLICY_PUBLISHED, null, null, null,
                "property " + propertyId + " version " + policy.getVersion());
        return policy;
    }

    static void validate(PolicyRequest request) {
        if (request.maxPriceFactor().compareTo(request.minPriceFactor()) < 0) {
            throw ApiException.unprocessable("INVALID_PRICE_LIMITS", "maxPriceFactor must be at least minPriceFactor.");
        }
        List<SeasonDto> seasons = request.seasons().stream().sorted(Comparator.comparing(SeasonDto::startDate)).toList();
        for (int i = 0; i < seasons.size(); i++) {
            SeasonDto s = seasons.get(i);
            if (s.endDate().isBefore(s.startDate())) {
                throw ApiException.unprocessable("INVALID_SEASON", "Season '" + s.name() + "' ends before it starts.");
            }
            if (i > 0 && !s.startDate().isAfter(seasons.get(i - 1).endDate())) {
                throw ApiException.unprocessable("OVERLAPPING_SEASONS",
                        "Seasons '" + seasons.get(i - 1).name() + "' and '" + s.name() + "' overlap.");
            }
        }
        var thresholds = new HashSet<BigDecimal>();
        boolean hasBaseTier = false;
        for (DemandTierDto tier : request.demandTiers()) {
            if (!thresholds.add(tier.minOccupancy().stripTrailingZeros())) {
                throw ApiException.unprocessable("DUPLICATE_DEMAND_TIER", "Demand tier thresholds must be unique.");
            }
            hasBaseTier |= tier.minOccupancy().signum() == 0;
        }
        if (!hasBaseTier) {
            throw ApiException.unprocessable("MISSING_BASE_DEMAND_TIER", "A demand tier starting at occupancy 0 is required.");
        }
    }
}
