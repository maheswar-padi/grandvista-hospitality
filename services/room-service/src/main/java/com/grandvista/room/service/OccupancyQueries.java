package com.grandvista.room.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Occupancy aggregates computed in the database with a single query per call (no per-night round trips). */
@Repository
public class OccupancyQueries {

    public record DailyOccupancy(LocalDate date, long allocatedRooms, long sellableRooms, BigDecimal ratio) {
    }

    private static final String ACTIVE_ALLOCATION = """
            (a.status = 'CONFIRMED' OR (a.status = 'HELD' AND a.hold_expires_at > :now))
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public OccupancyQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Occupancy ratio of one room type for every night in [from, to). */
    public Map<LocalDate, BigDecimal> byRoomType(UUID roomTypeId, LocalDate from, LocalDate to, Instant now) {
        var params = new MapSqlParameterSource()
                .addValue("from", Date.valueOf(from)).addValue("to", Date.valueOf(to))
                .addValue("typeId", roomTypeId).addValue("now", Timestamp.from(now));
        Long sellable = jdbc.queryForObject(
                "SELECT count(*) FROM rooms WHERE room_type_id = :typeId AND status = 'ACTIVE'", params, Long.class);
        List<DailyOccupancy> rows = jdbc.query("""
                SELECT n::date AS night, count(a.id) AS allocated
                FROM generate_series(CAST(:from AS date), CAST(:to AS date) - 1, interval '1 day') AS n
                LEFT JOIN inventory_allocations a
                       ON a.stay_range @> n::date AND %s
                      AND a.room_id IN (SELECT id FROM rooms WHERE room_type_id = :typeId)
                GROUP BY n ORDER BY n
                """.formatted(ACTIVE_ALLOCATION), params,
                (rs, i) -> toDaily(rs.getDate("night").toLocalDate(), rs.getLong("allocated"), sellable));
        Map<LocalDate, BigDecimal> result = new LinkedHashMap<>();
        rows.forEach(r -> result.put(r.date(), r.ratio()));
        return result;
    }

    /** Occupancy for the admin dashboard: one property, or the whole portfolio when {@code propertyId} is null. */
    public List<DailyOccupancy> hotelWide(UUID propertyId, LocalDate from, LocalDate to, Instant now) {
        var params = new MapSqlParameterSource()
                .addValue("from", Date.valueOf(from)).addValue("to", Date.valueOf(to)).addValue("now", Timestamp.from(now))
                .addValue("propertyId", propertyId);
        String roomFilter = propertyId == null ? "" : " AND property_id = :propertyId";
        Long sellable = jdbc.queryForObject("SELECT count(*) FROM rooms WHERE status = 'ACTIVE'" + roomFilter, params,
                Long.class);
        return jdbc.query("""
                SELECT n::date AS night, count(a.id) AS allocated
                FROM generate_series(CAST(:from AS date), CAST(:to AS date) - 1, interval '1 day') AS n
                LEFT JOIN inventory_allocations a ON a.stay_range @> n::date AND %s
                     %s
                GROUP BY n ORDER BY n
                """.formatted(ACTIVE_ALLOCATION, propertyId == null ? ""
                        : "AND a.room_id IN (SELECT id FROM rooms WHERE property_id = :propertyId)"), params,
                (rs, i) -> toDaily(rs.getDate("night").toLocalDate(), rs.getLong("allocated"), sellable));
    }

    private static DailyOccupancy toDaily(LocalDate night, long allocated, Long sellable) {
        long denominator = sellable == null || sellable == 0 ? 0 : sellable;
        BigDecimal ratio = denominator == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(allocated).divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP)
                        .min(BigDecimal.ONE);
        return new DailyOccupancy(night, allocated, denominator, ratio);
    }
}
