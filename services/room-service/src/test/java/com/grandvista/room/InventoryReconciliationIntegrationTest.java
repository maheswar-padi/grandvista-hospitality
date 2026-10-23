package com.grandvista.room;

import static com.grandvista.room.TestSupport.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grandvista.room.domain.ReconciliationRun;
import com.grandvista.room.reconciliation.BookingLookupClient;
import com.grandvista.room.reconciliation.InventoryReconciliationJob;
import com.grandvista.room.reconciliation.ReconciliationEngine.BookingFact;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.ResourceAccessException;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Stock reconciliation against a real PostgreSQL schema, with the booking-service replaced by a mock that returns
 * the booking side of each scenario. Every test uses its own property so runs do not see each other's data.
 */
@SpringBootTest(properties = {"eureka.client.enabled=false", "gv.demo.seed-enabled=false",
        "gv.media.local-root=target/test-media-reconciliation", "gv.security.jwt.jwk-set-uri=http://localhost:1/unused",
        "gv.inventory.reconciliation.initial-delay=1h"})
@AutoConfigureMockMvc
@Import(TestSupport.class)
@Testcontainers(disabledWithoutDocker = true)
class InventoryReconciliationIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TestSupport.MutableClock clock;
    @Autowired
    InventoryReconciliationJob job;
    @MockitoBean
    BookingLookupClient bookings;

    private final LocalDate base = LocalDate.now().plusDays(90);

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    /** Creates a property with one room type and {@code count} rooms; returns [propertyId, roomId...]. */
    private List<UUID> property(String code, int count) throws Exception {
        String request = """
                {"code":"%s","name":"GrandVista %s","city":"City %s","country":"IN","timeZone":"Asia/Kolkata"}
                """.formatted(code, code, code);
        UUID property = UUID.fromString(body(mvc.perform(post("/api/v1/admin/properties").with(user("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isCreated())).get("id").asText());
        String type = body(mvc.perform(post("/api/v1/admin/room-types").with(user("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"propertyId\":\"" + property + "\",\"code\":\"CLASSIC\",\"name\":\"Classic\",\"sortOrder\":1}"))
                .andExpect(status().isCreated())).get("id").asText();
        List<UUID> ids = new ArrayList<>(List.of(property));
        for (int i = 1; i <= count; i++) {
            String room = """
                    {"roomNumber":"%d","roomTypeId":"%s","name":"Room %d","maxOccupancy":2,
                     "bedConfiguration":"1 Queen bed","basePrice":3000.00,"currency":"INR","amenityCodes":[]}
                    """.formatted(100 + i, type, i);
            ids.add(UUID.fromString(body(mvc.perform(post("/api/v1/admin/rooms").with(user("ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON).content(room)).andExpect(status().isCreated())).get("id").asText()));
        }
        return ids;
    }

    private UUID allocation(UUID room, LocalDate in, String status, Instant holdExpiresAt, Instant updatedAt) {
        UUID booking = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO inventory_allocations (id, booking_id, room_id, check_in, check_out, guests, status,
                    hold_expires_at, confirmed_at, price_quote, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, 2, ?, ?, ?, '{}'::jsonb, ?, ?, 0)
                """, UUID.randomUUID(), booking, room, Date.valueOf(in), Date.valueOf(in.plusDays(2)), status,
                holdExpiresAt == null ? null : Timestamp.from(holdExpiresAt),
                "CONFIRMED".equals(status) ? Timestamp.from(updatedAt) : null, Timestamp.from(updatedAt),
                Timestamp.from(updatedAt));
        return booking;
    }

    private static BookingFact booking(UUID id, String status, UUID room, LocalDate in, Instant holdExpiresAt, Instant updated) {
        return new BookingFact(id, "GV-" + id.toString().substring(0, 6).toUpperCase(), status, room, in, in.plusDays(2),
                holdExpiresAt, updated);
    }

    private int events(String type, UUID bookingId) {
        return jdbc.queryForObject("SELECT count(*) FROM inventory_events WHERE event_type = ? AND booking_id = ?",
                Integer.class, type, bookingId);
    }

    @Test
    void reconciliationFlagsEveryMismatchReleasesExpiredOrphansAndAuditsOnce() throws Exception {
        List<UUID> p = property("RECA", 3);
        UUID property = p.get(0);
        UUID r1 = p.get(1);
        UUID r2 = p.get(2);
        UUID r3 = p.get(3);
        Instant now = clock.instant();
        Instant old = now.minus(Duration.ofHours(1));

        UUID expiredOrphan = allocation(r1, base, "HELD", now.minus(Duration.ofMinutes(30)), old);
        UUID liveHoldOfExpiredBooking = allocation(r1, base.plusDays(3), "HELD", now.plus(Duration.ofMinutes(5)), old);
        UUID confirmedUnknown = allocation(r2, base, "CONFIRMED", null, old);
        UUID confirmedButCancelled = allocation(r2, base.plusDays(3), "CONFIRMED", null, old);
        UUID consistent = allocation(r3, base, "CONFIRMED", null, old);
        UUID inFlight = allocation(r3, base.plusDays(3), "HELD", now.minus(Duration.ofSeconds(5)), now.minusSeconds(20));
        UUID confirmedNoRoom = UUID.randomUUID();
        UUID pendingNoHold = UUID.randomUUID();

        when(bookings.lookup(anyCollection())).thenReturn(List.of(
                booking(liveHoldOfExpiredBooking, "EXPIRED", r1, base.plusDays(3), null, old),
                booking(confirmedButCancelled, "CANCELLED", r2, base.plusDays(3), null, old),
                booking(consistent, "CONFIRMED", r3, base, null, old)));
        when(bookings.holdingInventory(anyCollection(), any())).thenReturn(List.of(
                booking(consistent, "CONFIRMED", r3, base, null, old),
                booking(confirmedNoRoom, "CONFIRMED", r3, base.plusDays(10), null, old),
                booking(pendingNoHold, "PENDING_PAYMENT", r3, base.plusDays(14), now.plus(Duration.ofMinutes(10)), old)));

        // customers cannot run or read it; staff can
        mvc.perform(post("/api/v1/admin/inventory/reconciliation/run").param("propertyId", property.toString())
                .with(user("CUSTOMER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/inventory/reconciliation").param("propertyId", property.toString())
                .with(user("STAFF"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECONCILIATION_NOT_RUN"));

        JsonNode first = body(mvc.perform(post("/api/v1/admin/inventory/reconciliation/run")
                .param("propertyId", property.toString()).with(user("STAFF"))).andExpect(status().isOk()));
        assertThat(first.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(first.get("trigger").asText()).isEqualTo("MANUAL");
        assertThat(first.get("propertyCode").asText()).isEqualTo("RECA");
        assertThat(first.get("allocationsChecked").asInt()).isEqualTo(6);
        assertThat(first.get("findingCount").asInt()).isEqualTo(6);
        assertThat(first.get("releasedCount").asInt()).isEqualTo(1);
        JsonNode counts = first.get("countsByType");
        assertThat(counts.get("ORPHAN_HOLD").asInt()).isEqualTo(2);
        assertThat(counts.get("ALLOCATION_WITHOUT_BOOKING").asInt()).isEqualTo(1);
        assertThat(counts.get("STATUS_MISMATCH").asInt()).isEqualTo(1);
        assertThat(counts.get("CONFIRMED_BOOKING_WITHOUT_ALLOCATION").asInt()).isEqualTo(1);
        assertThat(counts.get("PENDING_BOOKING_WITHOUT_HOLD").asInt()).isEqualTo(1);
        List<String> flagged = new ArrayList<>();
        first.get("findings").forEach(f -> flagged.add(f.get("bookingId").asText()));
        assertThat(flagged).doesNotContain(consistent.toString(), inFlight.toString());
        assertThat(first.get("findings").get(0).get("severity").asText()).isEqualTo("CRITICAL");

        // only the expired orphan was released; the live hold of an expired booking is left to expire on its own
        assertThat(jdbc.queryForMap("SELECT status, release_reason FROM inventory_allocations WHERE booking_id = ?",
                expiredOrphan)).containsEntry("status", "RELEASED").containsEntry("release_reason", "RECONCILIATION_ORPHAN_HOLD");
        assertThat(jdbc.queryForObject("SELECT status FROM inventory_allocations WHERE booking_id = ?", String.class,
                liveHoldOfExpiredBooking)).isEqualTo("HELD");
        assertThat(jdbc.queryForObject("SELECT status FROM inventory_allocations WHERE booking_id = ?", String.class,
                confirmedUnknown)).isEqualTo("CONFIRMED");
        assertThat(events("RECONCILIATION_FINDING", expiredOrphan)).isEqualTo(1);
        assertThat(events("RECONCILIATION_RELEASED", expiredOrphan)).isEqualTo(1);
        assertThat(events("RECONCILIATION_FINDING", confirmedNoRoom)).isEqualTo(1);

        // the scheduled run sees the same open problems: nothing new is logged, the released orphan is gone
        clock.advance(Duration.ofMinutes(1));
        job.reconcileAll();
        JsonNode latest = body(mvc.perform(get("/api/v1/admin/inventory/reconciliation")
                .param("propertyId", property.toString()).with(user("ADMIN"))).andExpect(status().isOk()));
        assertThat(latest.get("trigger").asText()).isEqualTo("SCHEDULED");
        assertThat(latest.get("actor").asText()).isEqualTo("system");
        assertThat(latest.get("findingCount").asInt()).isEqualTo(5);
        assertThat(latest.get("releasedCount").asInt()).isZero();
        assertThat(events("RECONCILIATION_FINDING", liveHoldOfExpiredBooking)).isEqualTo(1);
        assertThat(events("RECONCILIATION_FINDING", confirmedUnknown)).isEqualTo(1);

        JsonNode runs = body(mvc.perform(get("/api/v1/admin/inventory/reconciliation/runs")
                .param("propertyId", property.toString()).with(user("STAFF"))).andExpect(status().isOk()));
        assertThat(runs).hasSize(2);
        assertThat(runs.get(0).get("findings")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_events WHERE event_type = 'RECONCILIATION_RUN' "
                + "AND detail LIKE 'property=RECA %'", Integer.class)).isEqualTo(2);
    }

    @Test
    void bookingServiceOutageIsRecordedAsAFailedRunAndChangesNothing() throws Exception {
        List<UUID> p = property("RECB", 1);
        Instant now = clock.instant();
        UUID orphan = allocation(p.get(1), base, "HELD", now.minus(Duration.ofMinutes(30)), now.minus(Duration.ofHours(1)));
        when(bookings.lookup(anyCollection())).thenThrow(new ResourceAccessException("connection refused"));

        JsonNode report = body(mvc.perform(post("/api/v1/admin/inventory/reconciliation/run")
                .param("propertyId", p.get(0).toString()).with(user("ADMIN"))).andExpect(status().isOk()));

        assertThat(report.get("status").asText()).isEqualTo(ReconciliationRun.Status.FAILED.name());
        assertThat(report.get("error").asText()).startsWith("booking-service unavailable");
        assertThat(report.get("findingCount").asInt()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM inventory_allocations WHERE booking_id = ?", String.class, orphan))
                .isEqualTo("HELD");
    }

    @Test
    void propertyWithoutRoomsReconcilesCleanly() throws Exception {
        UUID property = property("RECC", 0).get(0);
        JsonNode report = body(mvc.perform(post("/api/v1/admin/inventory/reconciliation/run")
                .param("propertyId", property.toString()).with(user("ADMIN"))).andExpect(status().isOk()));
        assertThat(report.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(report.get("findingCount").asInt()).isZero();
        assertThat(report.get("allocationsChecked").asInt()).isZero();
    }
}
