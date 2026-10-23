package com.grandvista.room;

import static com.grandvista.room.TestSupport.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grandvista.room.demo.DemoDataSeeder;
import com.grandvista.room.repository.RoomRepository;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Administration of the catalogue (room types, amenities, rooms, images) and the demo data seeder, with the
 * seeder enabled so its start-up run is part of the test.
 */
@SpringBootTest(properties = {"eureka.client.enabled=false", "gv.demo.seed-enabled=true",
        "gv.media.local-root=target/test-media-admin", "gv.security.jwt.jwk-set-uri=http://localhost:1/unused",
        "gv.inventory.reconciliation.enabled=false"})
@AutoConfigureMockMvc
@Import(TestSupport.class)
@Testcontainers(disabledWithoutDocker = true)
class CatalogueAdminIntegrationTest {

    /** 1×1 transparent PNG. */
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    RoomRepository rooms;
    @Autowired
    DemoDataSeeder seeder;

    private JsonNode body(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void demoSeederCreatesTheFictionalCatalogueOnceWithIllustrations() throws Exception {
        // other tests in this class may add rooms numbered T-…; the seeded demo rooms are the others
        // three demo properties: the original hotel (11 rooms) and two more (4 rooms each)
        java.util.function.Function<String, Long> demoRooms = code -> rooms.findAll().stream()
                .filter(r -> r.getProperty().getCode().equals(code) && !r.getRoomNumber().startsWith("T-")).count();
        assertThat(demoRooms.apply("HYD")).isEqualTo(11);
        assertThat(demoRooms.apply("GOA")).isEqualTo(4);
        assertThat(demoRooms.apply("DXB")).isEqualTo(4);
        long before = rooms.count();
        seeder.run(new DefaultApplicationArguments());
        assertThat(rooms.count()).isEqualTo(before); // idempotent: never seeds a non-empty catalogue

        JsonNode page = body(mvc.perform(get("/api/v1/rooms").param("size", "50")).andExpect(status().isOk()));
        assertThat(page.toString()).contains("Classic Queen Room", "not a photograph");
    }

    @Test
    void administratorsManageTypesAmenitiesRoomsAndImages() throws Exception {
        JsonNode type = body(mvc.perform(post("/api/v1/admin/room-types").with(user("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"STUDIO_TEST\",\"name\":\"Studio\",\"description\":\"Compact\",\"sortOrder\":9}"))
                .andExpect(status().isCreated()));
        String typeId = type.get("id").asText();
        mvc.perform(post("/api/v1/admin/room-types").with(user("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"STUDIO_TEST\",\"name\":\"Again\",\"sortOrder\":1}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROOM_TYPE_CODE_TAKEN"));
        mvc.perform(put("/api/v1/admin/room-types/{id}", typeId).with(user("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"STUDIO_TEST\",\"name\":\"Studio Plus\",\"sortOrder\":8}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Studio Plus"));
        mvc.perform(put("/api/v1/admin/room-types/{id}", typeId).with(user("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"RENAMED\",\"name\":\"Studio\",\"sortOrder\":8}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("ROOM_TYPE_CODE_IMMUTABLE"));

        JsonNode amenity = body(mvc.perform(post("/api/v1/admin/amenities").with(user("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"YOGA_MAT\",\"name\":\"Yoga mat\",\"icon\":\"mat\",\"category\":\"WELLNESS\"}"))
                .andExpect(status().isCreated()));
        mvc.perform(put("/api/v1/admin/amenities/{id}", amenity.get("id").asText()).with(user("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"YOGA_MAT\",\"name\":\"Yoga mat & blocks\",\"icon\":\"mat\",\"category\":\"WELLNESS\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Yoga mat & blocks"));

        String roomBody = """
                {"roomNumber":"T-901","roomTypeId":"%s","name":"Test Studio","description":"For tests","maxOccupancy":2,
                 "bedConfiguration":"1 Queen bed","sizeSqM":20,"floor":9,"viewType":"City","basePrice":3999.00,
                 "currency":"INR","amenityCodes":["YOGA_MAT","WIFI"]%s}
                """;
        JsonNode room = body(mvc.perform(post("/api/v1/admin/rooms").with(user("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                .content(roomBody.formatted(typeId, ""))).andExpect(status().isCreated()));
        String roomId = room.get("id").asText();
        long version = room.get("version").asLong();
        mvc.perform(post("/api/v1/admin/rooms").with(user("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content(roomBody.formatted(typeId, "")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROOM_NUMBER_TAKEN"));
        mvc.perform(put("/api/v1/admin/rooms/{id}", roomId).with(user("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content(roomBody.formatted(typeId, ",\"version\":" + version).replace("3999.00", "4199.00")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.basePrice").value(4199.00));
        mvc.perform(put("/api/v1/admin/rooms/{id}", roomId).with(user("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content(roomBody.formatted(typeId, ",\"version\":" + version)))
                .andExpect(status().isConflict()); // stale version: optimistic concurrency

        JsonNode image = body(mvc.perform(multipart("/api/v1/admin/rooms/{id}/images", roomId)
                        .file(new MockMultipartFile("file", "room.png", "image/png", PNG)).param("altText", "Test studio")
                        .with(user("ADMIN")))
                .andExpect(status().isCreated()));
        mvc.perform(multipart("/api/v1/admin/rooms/{id}/images", roomId)
                        .file(new MockMultipartFile("file", "evil.png", "image/png", "<svg onload=alert(1)>".getBytes()))
                        .with(user("ADMIN")))
                .andExpect(status().is4xxClientError());
        mvc.perform(delete("/api/v1/admin/rooms/{id}/images/{imageId}", roomId, image.get("id").asText()).with(user("ADMIN")))
                .andExpect(status().is2xxSuccessful());
        mvc.perform(delete("/api/v1/admin/rooms/{id}/images/{imageId}", roomId, image.get("id").asText()).with(user("ADMIN")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("IMAGE_NOT_FOUND"));
    }

    @Test
    void staffMayNotEditTheCatalogue() throws Exception {
        mvc.perform(post("/api/v1/admin/room-types").with(user("STAFF")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"NOPE\",\"name\":\"Nope\",\"sortOrder\":1}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/amenities").with(user("CUSTOMER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"NOPE\",\"name\":\"Nope\",\"category\":\"GENERAL\"}"))
                .andExpect(status().isForbidden());
    }
}
