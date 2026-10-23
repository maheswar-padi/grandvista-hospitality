package com.grandvista.room.service;

import com.grandvista.platform.error.ApiException;
import com.grandvista.platform.web.PageResponse;
import com.grandvista.platform.persistence.Specs;
import com.grandvista.room.domain.AllocationStatus;
import com.grandvista.room.domain.InventoryAllocation;
import com.grandvista.room.domain.InventoryEvent;
import com.grandvista.room.domain.RoomStatus;
import com.grandvista.room.repository.InventoryAllocationRepository;
import com.grandvista.room.repository.InventoryEventRepository;
import com.grandvista.room.repository.RoomRepository;
import com.grandvista.room.web.dto.InventoryDtos.AllocationResponse;
import com.grandvista.room.web.dto.InventoryDtos.InventoryEventResponse;
import com.grandvista.room.web.dto.InventoryDtos.InventorySummary;
import com.grandvista.room.web.dto.InventoryDtos.OccupancyPoint;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only operational views for staff and administrators (dashboard figures come from real data). */
@Service
public class AdminInventoryService {

    private static final int MAX_OCCUPANCY_RANGE_DAYS = 366;

    private final InventoryAllocationRepository allocations;
    private final InventoryEventRepository events;
    private final RoomRepository rooms;
    private final OccupancyQueries occupancy;
    private final NamedParameterJdbcTemplate jdbc;
    private final StayRules stayRules;
    private final Clock clock;

    public AdminInventoryService(InventoryAllocationRepository allocations, InventoryEventRepository events,
            RoomRepository rooms, OccupancyQueries occupancy, NamedParameterJdbcTemplate jdbc, StayRules stayRules,
            Clock clock) {
        this.allocations = allocations;
        this.events = events;
        this.rooms = rooms;
        this.occupancy = occupancy;
        this.jdbc = jdbc;
        this.stayRules = stayRules;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<AllocationResponse> allocations(UUID propertyId, UUID roomId, Collection<AllocationStatus> statuses,
            LocalDate from, LocalDate to, int page, int size) {
        LocalDate start = from == null ? stayRules.today().minusDays(30) : from;
        LocalDate end = to == null ? start.plusDays(365) : to;
        Collection<AllocationStatus> effective = statuses == null || statuses.isEmpty()
                ? EnumSet.allOf(AllocationStatus.class) : statuses;
        Specification<InventoryAllocation> byProperty = propertyId == null ? null : (root, query, cb) -> {
            var sub = query.subquery(UUID.class);
            var room = sub.from(com.grandvista.room.domain.Room.class);
            sub.select(room.get("id")).where(cb.equal(room.get("property").get("id"), propertyId));
            return root.get("roomId").in(sub);
        };
        Specification<InventoryAllocation> filter = Specs.allOf(byProperty, Specs.eq("roomId", roomId),
                (root, query, cb) -> root.get("status").in(effective),
                (root, query, cb) -> cb.lessThan(root.get("checkIn"), end),
                (root, query, cb) -> cb.greaterThan(root.get("checkOut"), start));
        return PageResponse.of(allocations.findAll(filter, PageRequest.of(page, size, Sort.by("checkIn").ascending())),
                AllocationResponse::from);
    }

    @Transactional(readOnly = true)
    public PageResponse<InventoryEventResponse> events(UUID roomId, UUID bookingId, int page, int size) {
        Specification<InventoryEvent> filter = Specs.allOf(Specs.eq("roomId", roomId), Specs.eq("bookingId", bookingId));
        return PageResponse.of(events.findAll(filter, PageRequest.of(page, size, Sort.by("occurredAt").descending())),
                InventoryEventResponse::from);
    }

    @Transactional(readOnly = true)
    public List<OccupancyPoint> occupancy(UUID propertyId, LocalDate from, LocalDate to) {
        LocalDate start = from == null ? stayRules.today() : from;
        LocalDate end = to == null ? start.plusDays(30) : to;
        if (!end.isAfter(start) || start.plusDays(MAX_OCCUPANCY_RANGE_DAYS).isBefore(end)) {
            throw ApiException.badRequest("INVALID_DATE_RANGE", "Range must be 1 to " + MAX_OCCUPANCY_RANGE_DAYS + " days.");
        }
        return occupancy.hotelWide(propertyId, start, end, clock.instant()).stream()
                .map(d -> new OccupancyPoint(d.date(), d.allocatedRooms(), d.sellableRooms(), d.ratio().doubleValue()))
                .toList();
    }

    @Transactional(readOnly = true)
    public InventorySummary summary(UUID propertyId) {
        LocalDate today = stayRules.today();
        var todayOccupancy = occupancy.hotelWide(propertyId, today, today.plusDays(1), clock.instant());
        var params = new java.util.HashMap<String, Object>();
        params.put("today", Date.valueOf(today));
        params.put("propertyId", propertyId);
        Map<String, Object> movements = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE check_in = :today) AS arrivals,
                       count(*) FILTER (WHERE check_out = :today) AS departures
                FROM inventory_allocations WHERE status = 'CONFIRMED'
                """ + (propertyId == null ? "" : " AND room_id IN (SELECT id FROM rooms WHERE property_id = :propertyId)"),
                params);
        java.util.function.Function<RoomStatus, Long> count = status -> propertyId == null ? rooms.countByStatus(status)
                : rooms.countByPropertyIdAndStatus(propertyId, status);
        return new InventorySummary(propertyId == null ? rooms.count() : rooms.countByPropertyId(propertyId),
                count.apply(RoomStatus.ACTIVE), count.apply(RoomStatus.MAINTENANCE), count.apply(RoomStatus.OUT_OF_SERVICE),
                count.apply(RoomStatus.INACTIVE),
                todayOccupancy.isEmpty() ? 0 : todayOccupancy.get(0).ratio().doubleValue(),
                ((Number) movements.get("arrivals")).longValue(), ((Number) movements.get("departures")).longValue());
    }
}
