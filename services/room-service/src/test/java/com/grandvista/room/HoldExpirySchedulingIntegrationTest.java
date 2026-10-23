package com.grandvista.room;

import static com.grandvista.room.TestSupport.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grandvista.room.domain.AllocationStatus;
import com.grandvista.room.service.InventoryService;
import com.grandvista.room.web.dto.InventoryDtos.HoldRequest;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Hold expiry driven by the REAL scheduled sweeper (not by calling the service directly), plus the
 * properties a reservation lifecycle needs: restart safety (state lives in the database, no in-memory
 * timers) and exactly-once processing when several sweepers run at the same time.
 */
@SpringBootTest(properties = {"eureka.client.enabled=false", "gv.demo.seed-enabled=false",
        "gv.media.local-root=target/test-media", "gv.security.jwt.jwk-set-uri=http://localhost:1/unused",
        "gv.inventory.reconciliation.enabled=false",
        "gv.inventory.hold-sweep-interval=300ms", "gv.inventory.hold-sweep-initial-delay=0s"})
@AutoConfigureMockMvc
@Import(TestSupport.class)
@Testcontainers(disabledWithoutDocker = true)
class HoldExpirySchedulingIntegrationTest {

    private static final String CLASSIC_TYPE = "a1000000-0000-4000-8000-000000000001";

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    InventoryService inventory;
    @Autowired
    TestSupport.MutableClock clock;
    @Autowired
    JdbcTemplate jdbc;

    private final LocalDate base = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(60);

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private UUID createRoom() throws Exception {
        String number = "X" + (int) (Math.random() * 1_000_000);
        String body = """
                {"roomNumber":"%s","roomTypeId":"%s","name":"Expiry room %s","maxOccupancy":2,
                 "bedConfiguration":"1 Queen bed","basePrice":3000.00,"currency":"INR","amenityCodes":[]}
                """.formatted(number, CLASSIC_TYPE, number);
        return UUID.fromString(json.readTree(mvc.perform(post("/api/v1/admin/rooms").with(user("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText());
    }

    private String allocationStatus(UUID bookingId) {
        return jdbc.queryForObject("SELECT status FROM inventory_allocations WHERE booking_id = ?", String.class, bookingId);
    }

    private static void await(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within " + timeout);
            }
            Thread.sleep(100);
        }
    }

    @Test
    void scheduledSweeperExpiresLapsedHoldAndTheRoomBecomesBookableAgain() throws Exception {
        UUID room = createRoom();
        UUID booking = UUID.randomUUID();
        inventory.hold(new HoldRequest(booking, room, base, base.plusDays(2), 2));
        mvc.perform(get("/api/v1/rooms/{id}/availability", room).param("checkIn", base.toString())
                .param("checkOut", base.plusDays(2).toString())).andExpect(jsonPath("$.available").value(false));

        clock.advance(Duration.ofMinutes(16)); // hold TTL is 15 minutes
        await(() -> "EXPIRED".equals(allocationStatus(booking)), Duration.ofSeconds(10));

        Integer events = jdbc.queryForObject("SELECT count(*) FROM inventory_events WHERE booking_id = ? "
                + "AND event_type = 'HOLD_EXPIRED' AND actor = 'system'", Integer.class, booking);
        assertThat(events).isEqualTo(1);
        mvc.perform(get("/api/v1/rooms/{id}/availability", room).param("checkIn", base.toString())
                .param("checkOut", base.plusDays(2).toString())).andExpect(jsonPath("$.available").value(true));
        assertThat(inventory.hold(new HoldRequest(UUID.randomUUID(), room, base, base.plusDays(2), 2)).status())
                .isEqualTo(AllocationStatus.HELD);
    }

    @Test
    void holdLeftBehindWhileTheServiceWasDownIsExpiredFromDatabaseStateAlone() throws Exception {
        // Simulates a hold written before a crash/restart: no timer exists for it in this JVM.
        UUID room = createRoom();
        UUID booking = UUID.randomUUID();
        Instant past = clock.instant().minus(Duration.ofHours(1));
        jdbc.update("""
                INSERT INTO inventory_allocations (id, booking_id, room_id, check_in, check_out, guests, status,
                    hold_expires_at, price_quote, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, 2, 'HELD', ?, '{}'::jsonb, ?, ?, 0)
                """, UUID.randomUUID(), booking, room, Date.valueOf(base.plusDays(5)), Date.valueOf(base.plusDays(6)),
                Timestamp.from(past), Timestamp.from(past.minusSeconds(900)), Timestamp.from(past.minusSeconds(900)));

        await(() -> "EXPIRED".equals(allocationStatus(booking)), Duration.ofSeconds(10));
    }

    @Test
    void concurrentSweepersExpireEachHoldExactlyOnce() throws Exception {
        UUID room = createRoom();
        List<UUID> bookings = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            UUID booking = UUID.randomUUID();
            bookings.add(booking);
            inventory.hold(new HoldRequest(booking, room, base.plusDays(10 + i * 2), base.plusDays(11 + i * 2), 1));
        }
        clock.advance(Duration.ofMinutes(16));

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> runs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            runs.add(pool.submit(() -> {
                start.await();
                return inventory.expireDueHolds(100);
            }));
        }
        start.countDown();
        int expiredByOurSweepers = 0;
        for (Future<Integer> f : runs) {
            expiredByOurSweepers += f.get();
        }
        pool.shutdown();
        await(() -> bookings.stream().allMatch(b -> "EXPIRED".equals(allocationStatus(b))), Duration.ofSeconds(10));

        // the scheduled sweeper runs concurrently too; whoever wins, every hold is expired exactly once
        assertThat(expiredByOurSweepers).isLessThanOrEqualTo(bookings.size());
        for (UUID booking : bookings) {
            Integer events = jdbc.queryForObject("SELECT count(*) FROM inventory_events WHERE booking_id = ? "
                    + "AND event_type = 'HOLD_EXPIRED'", Integer.class, booking);
            assertThat(events).as("expiry events for %s", booking).isEqualTo(1);
        }
        assertThat(inventory.expireDueHolds(100)).isZero(); // re-running is a no-op
    }
}
