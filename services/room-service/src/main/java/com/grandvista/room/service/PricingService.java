package com.grandvista.room.service;

import com.grandvista.platform.error.ApiException;
import com.grandvista.room.config.HotelProperties;
import com.grandvista.room.domain.Room;
import com.grandvista.room.pricing.PriceQuote;
import com.grandvista.room.pricing.PricingEngine;
import com.grandvista.room.pricing.PricingPolicySnapshot;
import com.grandvista.room.pricing.StayDates;
import com.grandvista.room.repository.PricingPolicyRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Gathers the inputs for {@link PricingEngine} (active policy + live occupancy) and returns a quote. */
@Service
public class PricingService {

    private final PricingPolicyRepository policies;
    private final OccupancyQueries occupancy;
    private final HotelProperties hotel;
    private final Clock clock;

    public PricingService(PricingPolicyRepository policies, OccupancyQueries occupancy, HotelProperties hotel, Clock clock) {
        this.policies = policies;
        this.occupancy = occupancy;
        this.hotel = hotel;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PricingPolicySnapshot activePolicy() {
        return policies.findActive()
                .orElseThrow(() -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PRICING_POLICY_MISSING",
                        "Pricing is temporarily unavailable."))
                .toSnapshot();
    }

    @Transactional(readOnly = true)
    public PriceQuote quote(Room room, StayDates stay) {
        return quote(room, stay, activePolicy());
    }

    @Transactional(readOnly = true)
    public PriceQuote quote(Room room, StayDates stay, PricingPolicySnapshot policy) {
        Instant now = clock.instant();
        Map<LocalDate, BigDecimal> demand = occupancy.byRoomType(room.getRoomType().getId(), stay.checkIn(),
                stay.checkOut(), now);
        return PricingEngine.quote(room.getBasePrice(), room.getCurrency(), stay, policy,
                night -> demand.getOrDefault(night, BigDecimal.ZERO), now, hotel.quoteValidity());
    }
}
