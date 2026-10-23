package com.grandvista.room;

import static com.grandvista.room.TestSupport.serviceToken;
import static com.grandvista.room.TestSupport.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grandvista.platform.error.ApiException;
import com.grandvista.room.domain.AllocationStatus;
import com.grandvista.room.media.MediaStorageTest;
import com.grandvista.room.service.InventoryService;
import com.grandvista.room.web.dto.InventoryDtos.HoldRequest;
import com.grandvista.room.web.dto.InventoryDtos.HoldResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {"eureka.client.enabled=false", "gv.demo.seed-enabled=false",
        "gv.media.local-root=target/test-media", "gv.security.jwt.jwk-set-uri=http://localhost:1/unused",
        "gv.inventory.reconciliation.enabled=false"})
@AutoConfigureMockMvc
@Import(TestSupport.class)
@Testcontainers(disabledWithoutDocker = true)
class RoomInventoryIntegrationTest {

    private static final String CLASSIC_TYPE = "a1000000-0000-4000-8000-000000000001";

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    InventoryService inventory;
    @Autowired
    TestSupport.MutableClock clock;

    private final LocalDate base = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(20);

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private UUID createRoom(int maxOccupancy) throws Exception {
        String number = "T" + (int) (Math.random() * 1_000_000);
        String body = """
                {"roomNumber":"%s","roomTypeId":"%s","name":"Test room %s","description":"Integration test room",
                 "maxOccupancy":%d,"bedConfiguration":"1 Queen bed","basePrice":5000.00,"currency":"INR",
                 "amenityCodes":["WIFI","SEA_VIEW"]}
                """.formatted(number, CLASSIC_TYPE, number, maxOccupancy);
        String response = mvc.perform(post("/api/v1/admin/rooms").with(user("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(response).get("id").asText());
    }

    private HoldRequest hold(UUID room, int fromDay, int toDay) {
        return new HoldRequest(UUID.randomUUID(), room, base.plusDays(fromDay), base.plusDays(toDay), 2);
    }

    @Test
    void concurrentOverlappingHoldsAllowExactlyOneWinner() throws Exception {
        UUID room = createRoom(2);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int offset = i % 2; // stays [0,3) and [1,4) overlap each other
            Callable<String> task = () -> {
                start.await();
                try {
                    inventory.hold(hold(room, offset, offset + 3));
                    return "OK";
                } catch (ApiException ex) {
                    return ex.getCode();
                }
            };
            results.add(pool.submit(task));
        }
        start.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> f : results) {
            outcomes.add(f.get());
        }
        pool.shutdown();

        assertThat(outcomes).filteredOn("OK"::equals).hasSize(1);
        assertThat(outcomes).filteredOn(o -> !o.equals("OK")).allMatch("ROOM_UNAVAILABLE"::equals);
    }

    @Test
    void nonOverlappingAndBackToBackStaysCanBothBeHeld() throws Exception {
        UUID room = createRoom(2);
        assertThat(inventory.hold(hold(room, 0, 3)).status()).isEqualTo(AllocationStatus.HELD);
        assertThat(inventory.hold(hold(room, 3, 5)).status()).isEqualTo(AllocationStatus.HELD); // check-out day reused
        assertThat(inventory.hold(hold(room, 7, 9)).status()).isEqualTo(AllocationStatus.HELD);
        assertThatThrownBy(() -> inventory.hold(hold(room, 4, 8)))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "ROOM_UNAVAILABLE");
    }

    @Test
    void holdIsIdempotentPerBookingAndKeepsOriginalPrice() throws Exception {
        UUID room = createRoom(2);
        HoldRequest request = hold(room, 0, 2);
        HoldResponse first = inventory.hold(request);
        HoldResponse replay = inventory.hold(request);

        assertThat(replay.allocationId()).isEqualTo(first.allocationId());
        assertThat(replay.quote().total()).isEqualByComparingTo(first.quote().total());
        assertThat(first.quote().nights()).isEqualTo(2);

        HoldRequest mismatched = new HoldRequest(request.bookingId(), room, base.plusDays(5), base.plusDays(6), 2);
        assertThatThrownBy(() -> inventory.hold(mismatched)).hasFieldOrPropertyWithValue("code", "HOLD_MISMATCH");
    }

    @Test
    void confirmAndReleaseAreIdempotentAndReleaseFreesTheDates() throws Exception {
        UUID room = createRoom(2);
        HoldRequest request = hold(room, 0, 2);
        inventory.hold(request);

        assertThat(inventory.confirm(request.bookingId()).status()).isEqualTo(AllocationStatus.CONFIRMED);
        assertThat(inventory.confirm(request.bookingId()).status()).isEqualTo(AllocationStatus.CONFIRMED);
        assertThatThrownBy(() -> inventory.hold(hold(room, 1, 3))).hasFieldOrPropertyWithValue("code", "ROOM_UNAVAILABLE");

        inventory.release(request.bookingId(), "CANCELLED");
        inventory.release(request.bookingId(), "CANCELLED");
        inventory.release(UUID.randomUUID(), "NEVER_HELD"); // unknown booking: no-op
        assertThat(inventory.get(request.bookingId()).status()).isEqualTo(AllocationStatus.RELEASED);
        assertThat(inventory.hold(hold(room, 1, 3)).status()).isEqualTo(AllocationStatus.HELD);
        assertThatThrownBy(() -> inventory.confirm(request.bookingId())).hasFieldOrPropertyWithValue("code", "HOLD_RELEASED");
    }

    @Test
    void expiredHoldStopsBlockingAndIsReacquiredOnConfirmWhenStillFree() throws Exception {
        UUID room = createRoom(2);
        HoldRequest lapsed = hold(room, 0, 2);
        inventory.hold(lapsed);

        clock.advance(Duration.ofMinutes(16));
        assertThat(inventory.expireDueHolds(100)).isGreaterThanOrEqualTo(1);
        assertThat(inventory.get(lapsed.bookingId()).status()).isEqualTo(AllocationStatus.EXPIRED);

        // late payment success: room still free, so the allocation is re-acquired
        assertThat(inventory.confirm(lapsed.bookingId()).status()).isEqualTo(AllocationStatus.CONFIRMED);
    }

    @Test
    void expiredHoldTakenBySomeoneElseCannotBeConfirmed() throws Exception {
        UUID room = createRoom(2);
        HoldRequest lapsed = hold(room, 0, 2);
        inventory.hold(lapsed);
        clock.advance(Duration.ofMinutes(16));

        // a new guest grabs the dates; the stale hold is expired lazily inside this hold
        assertThat(inventory.hold(hold(room, 1, 3)).status()).isEqualTo(AllocationStatus.HELD);
        assertThatThrownBy(() -> inventory.confirm(lapsed.bookingId()))
                .hasFieldOrPropertyWithValue("code", "INVENTORY_NO_LONGER_AVAILABLE");
    }

    @Test
    void roomUnderMaintenanceCannotBeHeldAndCapacityIsEnforced() throws Exception {
        UUID room = createRoom(2);
        assertThatThrownBy(() -> inventory.hold(new HoldRequest(UUID.randomUUID(), room, base, base.plusDays(1), 3)))
                .hasFieldOrPropertyWithValue("code", "GUEST_COUNT_EXCEEDS_CAPACITY");

        mvc.perform(put("/api/v1/admin/rooms/{id}/status", room).with(user("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"MAINTENANCE\",\"reason\":\"Leak\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.room.status").value("MAINTENANCE"));
        assertThatThrownBy(() -> inventory.hold(hold(room, 0, 1))).hasFieldOrPropertyWithValue("code", "ROOM_NOT_AVAILABLE");
    }

    @Test
    void searchExcludesRoomsBookedForOverlappingDatesAndReturnsDynamicPrice() throws Exception {
        UUID room = createRoom(4);
        inventory.hold(hold(room, 2, 4));

        String overlapping = mvc.perform(get("/api/v1/rooms").param("checkIn", base.plusDays(3).toString())
                        .param("checkOut", base.plusDays(5).toString()).param("guests", "4").param("size", "50"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(ids(overlapping)).doesNotContain(room.toString());

        String free = mvc.perform(get("/api/v1/rooms").param("checkIn", base.plusDays(4).toString())
                        .param("checkOut", base.plusDays(6).toString()).param("guests", "4")
                        .param("amenities", "SEA_VIEW", "WIFI").param("size", "50"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(ids(free)).contains(room.toString());
        JsonNode match = find(free, room);
        assertThat(match.get("stayPrice").get("nights").asInt()).isEqualTo(2);
        assertThat(match.get("stayPrice").get("total").decimalValue()).isPositive();
    }

    @Test
    void publicEndpointsValidateDatesAndQuoteIsItemised() throws Exception {
        UUID room = createRoom(2);
        mvc.perform(get("/api/v1/rooms/{id}/quote", room).param("checkIn", base.plusDays(2).toString())
                        .param("checkOut", base.toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
        mvc.perform(get("/api/v1/rooms/{id}/quote", room).param("checkIn", base.minusDays(40).toString())
                        .param("checkOut", base.minusDays(39).toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CHECK_IN_IN_PAST"));
        mvc.perform(get("/api/v1/rooms/{id}/quote", room).param("checkIn", "not-a-date").param("checkOut", base.toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/api/v1/rooms/{id}", UUID.randomUUID())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"));

        mvc.perform(get("/api/v1/rooms/{id}/quote", room).param("checkIn", base.toString())
                        .param("checkOut", base.plusDays(3).toString()).param("guests", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.quote.nights").value(3))
                .andExpect(jsonPath("$.quote.nightlyRates.length()").value(3))
                .andExpect(jsonPath("$.quote.pricingPolicyVersion").isNumber());
    }

    @Test
    void authorizationIsEnforcedPerEndpoint() throws Exception {
        String holdBody = "{\"bookingId\":\"%s\",\"roomId\":\"%s\",\"checkIn\":\"%s\",\"checkOut\":\"%s\",\"guests\":1}"
                .formatted(UUID.randomUUID(), createRoom(2), base, base.plusDays(1));

        mvc.perform(post("/internal/v1/holds").contentType(MediaType.APPLICATION_JSON).content(holdBody))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/v1/holds").with(user("ADMIN")).contentType(MediaType.APPLICATION_JSON).content(holdBody))
                .andExpect(status().isForbidden());
        mvc.perform(post("/internal/v1/holds").with(serviceToken()).contentType(MediaType.APPLICATION_JSON).content(holdBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("HELD"))
                .andExpect(jsonPath("$.quote.total").isNumber());

        mvc.perform(post("/api/v1/admin/rooms").with(user("CUSTOMER")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/rooms").with(user("STAFF")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/inventory/summary").with(user("STAFF"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/inventory/summary").with(user("CUSTOMER"))).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }

    @Test
    void imageUploadAcceptsRealImagesAndRejectsDisguisedFiles() throws Exception {
        UUID room = createRoom(2);
        var png = new MockMultipartFile("file", "photo.png", "image/png", MediaStorageTest.PNG);
        String uploaded = mvc.perform(multipart("/api/v1/admin/rooms/{id}/images", room).file(png).with(user("ADMIN")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentType").value("image/png"))
                .andReturn().getResponse().getContentAsString();
        String url = json.readTree(uploaded).get("url").asText();
        mvc.perform(get(url)).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("X-Content-Type-Options", "nosniff"));

        var disguised = new MockMultipartFile("file", "evil.png", "image/png", "<svg onload=alert(1)></svg>".getBytes());
        mvc.perform(multipart("/api/v1/admin/rooms/{id}/images", room).file(disguised).with(user("ADMIN")))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_IMAGE_TYPE"));
    }

    @Test
    void optimisticLockingRejectsStaleUpdates() throws Exception {
        UUID room = createRoom(2);
        JsonNode current = json.readTree(mvc.perform(get("/api/v1/admin/rooms/{id}", room).with(user("ADMIN")))
                .andReturn().getResponse().getContentAsString());
        String update = """
                {"roomNumber":"%s","roomTypeId":"%s","name":"Renamed","maxOccupancy":2,"bedConfiguration":"1 King bed",
                 "basePrice":6100.00,"currency":"INR","amenityCodes":["WIFI"],"version":%d}
                """;
        String number = current.get("roomNumber").asText();
        long version = current.get("version").asLong();
        mvc.perform(put("/api/v1/admin/rooms/{id}", room).with(user("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content(update.formatted(number, CLASSIC_TYPE, version)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.basePrice").value(6100.00));
        mvc.perform(put("/api/v1/admin/rooms/{id}", room).with(user("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content(update.formatted(number, CLASSIC_TYPE, version)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    void adminSearchesWorkWithAndWithoutEachOptionalFilter() throws Exception {
        UUID room = createRoom(2);
        HoldResponse hold = inventory.hold(hold(room, 0, 2));
        var staff = user("STAFF");
        mvc.perform(get("/api/v1/admin/rooms").with(staff)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/rooms").param("status", "ACTIVE").param("q", "test room").with(staff))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", org.hamcrest.Matchers.hasItem(room.toString())));
        mvc.perform(get("/api/v1/admin/rooms").param("status", "INACTIVE").with(staff))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(room.toString()))));
        mvc.perform(get("/api/v1/admin/inventory/allocations").with(staff)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/inventory/allocations").param("roomId", room.toString()).param("status", "HELD")
                        .param("from", base.toString()).param("to", base.plusDays(5).toString()).with(staff))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/admin/inventory/events").with(staff)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/inventory/events").param("bookingId", hold.bookingId().toString()).with(staff))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].eventType").value("HOLD_CREATED"));
        mvc.perform(get("/api/v1/admin/inventory/occupancy").with(staff)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(30));
    }

    @Test
    void holdResponseHonoursPublishedContract() throws Exception {
        String body = "{\"bookingId\":\"%s\",\"roomId\":\"%s\",\"checkIn\":\"%s\",\"checkOut\":\"%s\",\"guests\":2}"
                .formatted(UUID.randomUUID(), createRoom(2), base.plusDays(40), base.plusDays(42));
        String actual = mvc.perform(post("/internal/v1/holds").with(serviceToken()).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode example = json.readTree(java.nio.file.Files.readString(
                java.nio.file.Path.of("../../contracts/room-service/hold-response.json")));
        assertHonoursContract(example, json.readTree(actual), "");
        assertThat(json.readTree(actual).get("checkIn").asText()).matches("\\d{4}-\\d{2}-\\d{2}");
    }

    /** Provider side of /contracts: every field of the published example must be present in the real response. */
    private static void assertHonoursContract(JsonNode example, JsonNode actual, String path) {
        example.fieldNames().forEachRemaining(field -> {
            assertThat(actual.has(field)).as("contract field %s%s", path, field).isTrue();
            JsonNode e = example.get(field);
            JsonNode a = actual.get(field);
            if (e.isObject() && a.isObject()) {
                assertHonoursContract(e, a, path + field + ".");
            } else if (e.isArray() && e.size() > 0 && a.isArray() && a.size() > 0) {
                assertHonoursContract(e.get(0), a.get(0), path + field + "[0].");
            }
        });
    }

    private List<String> ids(String page) throws Exception {
        List<String> ids = new ArrayList<>();
        json.readTree(page).get("content").forEach(n -> ids.add(n.get("id").asText()));
        return ids;
    }

    private JsonNode find(String page, UUID id) throws Exception {
        for (JsonNode n : json.readTree(page).get("content")) {
            if (n.get("id").asText().equals(id.toString())) {
                return n;
            }
        }
        throw new AssertionError("room not found in page");
    }
}
