package com.grandvista.room.service;

import com.grandvista.platform.error.ApiException;
import com.grandvista.platform.web.PageResponse;
import com.grandvista.room.domain.InventoryAllocation;
import com.grandvista.room.domain.Room;
import com.grandvista.room.domain.RoomImage;
import com.grandvista.room.domain.RoomStatus;
import com.grandvista.room.pricing.PriceQuote;
import com.grandvista.room.pricing.PricingPolicySnapshot;
import com.grandvista.room.pricing.StayDates;
import com.grandvista.room.repository.InventoryAllocationRepository;
import com.grandvista.room.repository.RoomImageRepository;
import com.grandvista.room.repository.RoomRepository;
import com.grandvista.room.web.dto.RoomDtos.AvailabilityResponse;
import com.grandvista.room.web.dto.RoomDtos.CalendarResponse;
import com.grandvista.room.web.dto.RoomDtos.QuoteResponse;
import com.grandvista.room.web.dto.RoomDtos.RoomResponse;
import com.grandvista.room.web.dto.RoomDtos.StayPrice;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Public read side: search, room details, availability, calendar and price quotes. */
@Service
public class RoomQueryService {

    public enum SortOrder {
        RECOMMENDED("rt.sort_order ASC, r.base_price ASC, r.room_number ASC"),
        PRICE_ASC("r.base_price ASC, r.room_number ASC"),
        PRICE_DESC("r.base_price DESC, r.room_number ASC"),
        CAPACITY_DESC("r.max_occupancy DESC, r.base_price ASC"),
        NAME_ASC("r.name ASC, r.room_number ASC");

        /** Whitelisted SQL fragments: client input selects an enum constant, never raw SQL. */
        private final String sql;

        SortOrder(String sql) {
            this.sql = sql;
        }
    }

    public record SearchCriteria(LocalDate checkIn, LocalDate checkOut, Integer guests, String roomTypeCode,
                                 BigDecimal minPrice, BigDecimal maxPrice, Set<String> amenityCodes, SortOrder sort,
                                 int page, int size) {
    }

    private static final int MAX_CALENDAR_DAYS = 120;

    private final NamedParameterJdbcTemplate jdbc;
    private final RoomRepository rooms;
    private final RoomImageRepository images;
    private final InventoryAllocationRepository allocations;
    private final PricingService pricing;
    private final OccupancyQueries occupancy;
    private final StayRules stayRules;
    private final Clock clock;

    public RoomQueryService(NamedParameterJdbcTemplate jdbc, RoomRepository rooms, RoomImageRepository images,
            InventoryAllocationRepository allocations, PricingService pricing, OccupancyQueries occupancy,
            StayRules stayRules, Clock clock) {
        this.jdbc = jdbc;
        this.rooms = rooms;
        this.images = images;
        this.allocations = allocations;
        this.pricing = pricing;
        this.occupancy = occupancy;
        this.stayRules = stayRules;
        this.clock = clock;
    }

    /**
     * Searches sellable rooms. When dates are given, rooms with an overlapping active allocation are excluded
     * and each result carries the dynamic price for the stay. Price filters apply to the base nightly rate.
     */
    @Transactional(readOnly = true)
    public PageResponse<RoomResponse> search(SearchCriteria c) {
        StayDates stay = null;
        if (c.checkIn() != null || c.checkOut() != null) {
            stay = stayRules.validate(c.checkIn(), c.checkOut());
        }
        if (c.minPrice() != null && c.maxPrice() != null && c.minPrice().compareTo(c.maxPrice()) > 0) {
            throw ApiException.badRequest("INVALID_PRICE_RANGE", "minPrice cannot exceed maxPrice.");
        }

        StringBuilder where = new StringBuilder("r.status = 'ACTIVE'");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (c.guests() != null) {
            where.append(" AND r.max_occupancy >= :guests");
            params.addValue("guests", c.guests());
        }
        if (c.roomTypeCode() != null) {
            where.append(" AND rt.code = :typeCode");
            params.addValue("typeCode", c.roomTypeCode());
        }
        if (c.minPrice() != null) {
            where.append(" AND r.base_price >= :minPrice");
            params.addValue("minPrice", c.minPrice());
        }
        if (c.maxPrice() != null) {
            where.append(" AND r.base_price <= :maxPrice");
            params.addValue("maxPrice", c.maxPrice());
        }
        if (c.amenityCodes() != null && !c.amenityCodes().isEmpty()) {
            where.append(" ").append("""
                     AND r.id IN (SELECT ra.room_id FROM room_amenities ra JOIN amenities am ON am.id = ra.amenity_id
                                  WHERE am.code IN (:amenities) GROUP BY ra.room_id
                                  HAVING count(DISTINCT am.code) = :amenityCount)""");
            params.addValue("amenities", c.amenityCodes()).addValue("amenityCount", c.amenityCodes().size());
        }
        if (stay != null) {
            where.append(" ").append("""
                     AND NOT EXISTS (SELECT 1 FROM inventory_allocations a
                                     WHERE a.room_id = r.id
                                       AND a.stay_range && daterange(:checkIn, :checkOut, '[)')
                                       AND (a.status = 'CONFIRMED' OR (a.status = 'HELD' AND a.hold_expires_at > :now)))""");
            params.addValue("checkIn", Date.valueOf(stay.checkIn())).addValue("checkOut", Date.valueOf(stay.checkOut()))
                    .addValue("now", Timestamp.from(clock.instant()));
        }
        String from = " FROM rooms r JOIN room_types rt ON rt.id = r.room_type_id WHERE " + where;
        Long total = jdbc.queryForObject("SELECT count(*)" + from, params, Long.class);
        params.addValue("limit", c.size()).addValue("offset", (long) c.page() * c.size());
        SortOrder sort = c.sort() == null ? SortOrder.RECOMMENDED : c.sort();
        List<UUID> ids = jdbc.queryForList("SELECT r.id" + from + " ORDER BY " + sort.sql + " LIMIT :limit OFFSET :offset",
                params, UUID.class);

        List<RoomResponse> content = toResponses(ids, stay);
        long totalElements = total == null ? 0 : total;
        int totalPages = (int) Math.ceil(totalElements / (double) c.size());
        return new PageResponse<>(content, c.page(), c.size(), totalElements, totalPages);
    }

    @Transactional(readOnly = true)
    public RoomResponse get(UUID roomId, boolean includeInactive) {
        Room room = rooms.findWithDetailsById(roomId)
                .filter(r -> includeInactive || r.getStatus() != RoomStatus.INACTIVE)
                .orElseThrow(() -> ApiException.notFound("ROOM_NOT_FOUND", "Room not found."));
        return RoomResponse.from(room, room.getImages(), null);
    }

    @Transactional(readOnly = true)
    public AvailabilityResponse availability(UUID roomId, LocalDate checkIn, LocalDate checkOut) {
        StayDates stay = stayRules.validate(checkIn, checkOut);
        Room room = publicRoom(roomId);
        String reason = unavailableReason(room, stay);
        return new AvailabilityResponse(roomId, checkIn, checkOut, reason == null, reason);
    }

    /** Nights that cannot be booked in [from, from+days); exposes dates only, never booking identities. */
    @Transactional(readOnly = true)
    public CalendarResponse calendar(UUID roomId, LocalDate from, int days) {
        if (days < 1 || days > MAX_CALENDAR_DAYS) {
            throw ApiException.badRequest("INVALID_CALENDAR_RANGE", "days must be between 1 and " + MAX_CALENDAR_DAYS + ".");
        }
        LocalDate start = from == null ? stayRules.today() : from;
        LocalDate end = start.plusDays(days);
        publicRoom(roomId);
        TreeSet<LocalDate> nights = new TreeSet<>();
        for (InventoryAllocation a : allocations.findBlocking(roomId, start, end, clock.instant())) {
            a.getCheckIn().datesUntil(a.getCheckOut()).filter(d -> !d.isBefore(start) && d.isBefore(end)).forEach(nights::add);
        }
        return new CalendarResponse(roomId, start, end, List.copyOf(nights));
    }

    @Transactional(readOnly = true)
    public QuoteResponse quote(UUID roomId, LocalDate checkIn, LocalDate checkOut, int guests) {
        StayDates stay = stayRules.validate(checkIn, checkOut);
        Room room = publicRoom(roomId);
        if (guests > room.getMaxOccupancy()) {
            throw ApiException.unprocessable("GUEST_COUNT_EXCEEDS_CAPACITY",
                    "This room accommodates at most " + room.getMaxOccupancy() + " guests.");
        }
        String reason = unavailableReason(room, stay);
        return new QuoteResponse(roomId, guests, reason == null, reason, pricing.quote(room, stay));
    }

    private String unavailableReason(Room room, StayDates stay) {
        if (!room.getStatus().isSellable()) {
            return "ROOM_" + room.getStatus().name();
        }
        return allocations.findBlocking(room.getId(), stay.checkIn(), stay.checkOut(), clock.instant()).isEmpty()
                ? null : "BOOKED_FOR_SELECTED_DATES";
    }

    private Room publicRoom(UUID roomId) {
        return rooms.findWithDetailsById(roomId)
                .filter(r -> r.getStatus() != RoomStatus.INACTIVE)
                .orElseThrow(() -> ApiException.notFound("ROOM_NOT_FOUND", "Room not found."));
    }

    /** Batch-loads rooms, amenities and images for a page (constant number of queries) and keeps SQL order. */
    public List<RoomResponse> toResponses(List<UUID> ids, StayDates stay) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, Room> byId = rooms.findAllByIdIn(ids).stream().collect(Collectors.toMap(Room::getId, Function.identity()));
        Map<UUID, List<RoomImage>> imagesByRoom = new HashMap<>();
        images.findForRooms(ids).forEach(row ->
                imagesByRoom.computeIfAbsent(row.getRoomId(), k -> new ArrayList<>()).add(row.getImage()));

        PricingPolicySnapshot policy = stay == null ? null : pricing.activePolicy();
        Map<UUID, Map<LocalDate, BigDecimal>> demandByType = new HashMap<>();
        List<RoomResponse> result = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            Room room = byId.get(id);
            if (room == null) {
                continue;
            }
            StayPrice price = null;
            if (stay != null) {
                StayDates s = stay;
                Map<LocalDate, BigDecimal> demand = demandByType.computeIfAbsent(room.getRoomType().getId(),
                        typeId -> occupancy.byRoomType(typeId, s.checkIn(), s.checkOut(), clock.instant()));
                PriceQuote quote = com.grandvista.room.pricing.PricingEngine.quote(room.getBasePrice(), room.getCurrency(),
                        stay, policy, night -> demand.getOrDefault(night, BigDecimal.ZERO), clock.instant(),
                        java.time.Duration.ZERO);
                price = StayPrice.from(quote);
            }
            result.add(RoomResponse.from(room, imagesByRoom.getOrDefault(id, List.of()), price));
        }
        return result;
    }
}
