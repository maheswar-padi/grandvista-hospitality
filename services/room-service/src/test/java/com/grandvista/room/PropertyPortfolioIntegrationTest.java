package com.grandvista.room;

import static com.grandvista.room.TestSupport.serviceToken;
import static com.grandvista.room.TestSupport.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The multi-property portfolio on a real database with the demo data seeded (Hyderabad 11 rooms, Goa 4, Dubai 4):
 * property-scoped search, pricing, catalogue rules, administration and inventory views.
 */
@SpringBootTest(properties = {"eureka.client.enabled=false", "gv.demo.seed-enabled=true",
        "gv.media.local-root=target/test-media-portfolio", "gv.security.jwt.jwk-set-uri=http://localhost:1/unused",
        "gv.inventory.reconciliation.enabled=false"})
@AutoConfigureMockMvc
@Import(TestSupport.class)
@Testcontainers(disabledWithoutDocker = true)
class PropertyPortfolioIntegrationTest {

    static final String HYD = "d1000000-0000-4000-8000-000000000001";
    static final String GOA = "d1000000-0000-4000-8000-000000000002";
    static final String DXB = "d1000000-0000-4000-8000-000000000003";

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private static List<String> values(JsonNode array, String path) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.at(path).asText()));
        return out;
    }

    /** A Wednesday→Thursday night a few weeks ahead (no weekend multiplier, no season). */
    private static LocalDate weekday() {
        return LocalDate.now(ZoneId.of("Asia/Kolkata")).plusWeeks(5).with(TemporalAdjusters.next(DayOfWeek.WEDNESDAY));
    }

    @Test
    void portfolioListsThreePropertiesAndSearchFiltersByCityAndProperty() throws Exception {
        JsonNode list = body(mvc.perform(get("/api/v1/properties")).andExpect(status().isOk()));
        assertThat(values(list, "/code")).containsExactlyInAnyOrder("HYD", "GOA", "DXB");
        assertThat(values(list, "/city")).containsExactlyInAnyOrder("Hyderabad", "Goa", "Dubai");
        JsonNode dubai = body(mvc.perform(get("/api/v1/properties/{id}", DXB)).andExpect(status().isOk()));
        assertThat(dubai.get("timeZone").asText()).isEqualTo("Asia/Dubai");
        assertThat(dubai.get("totalRooms").asLong()).isEqualTo(4);

        JsonNode goa = body(mvc.perform(get("/api/v1/rooms").param("city", "goa").param("size", "50")));
        assertThat(goa.get("totalElements").asLong()).isEqualTo(4);
        assertThat(values(goa.get("content"), "/property/code")).containsOnly("GOA");

        LocalDate in = weekday();
        JsonNode dxb = body(mvc.perform(get("/api/v1/rooms").param("propertyId", DXB).param("checkIn", in.toString())
                .param("checkOut", in.plusDays(1).toString()).param("size", "50")));
        assertThat(dxb.get("totalElements").asLong()).isEqualTo(4);
        assertThat(values(dxb.get("content"), "/property/city")).containsOnly("Dubai");
        assertThat(dxb.get("content").get(0).get("stayPrice").get("nights").asInt()).isEqualTo(1);

        JsonNode all = body(mvc.perform(get("/api/v1/rooms").param("size", "50")));
        assertThat(all.get("totalElements").asLong()).isGreaterThanOrEqualTo(19);
        // room numbers are unique per property, not globally: "101" exists in Hyderabad and Goa
        assertThat(values(all.get("content"), "/roomNumber").stream().filter("101"::equals).count()).isEqualTo(2);

        JsonNode types = body(mvc.perform(get("/api/v1/room-types").param("propertyId", GOA)));
        assertThat(values(types, "/propertyId")).containsOnly(GOA);
        assertThat(values(types, "/code")).contains("CLASSIC", "SUITE");
    }

    @Test
    void eachPropertyHasItsOwnPricingPolicy() throws Exception {
        LocalDate in = weekday();
        String dubaiRoom = body(mvc.perform(get("/api/v1/rooms").param("propertyId", DXB).param("size", "1")))
                .get("content").get(0).get("id").asText();
        String hyderabadRoom = body(mvc.perform(get("/api/v1/rooms").param("propertyId", HYD).param("size", "1")))
                .get("content").get(0).get("id").asText();
        JsonNode dxbBefore = body(mvc.perform(get("/api/v1/rooms/{id}/quote", dubaiRoom).param("checkIn", in.toString())
                .param("checkOut", in.plusDays(1).toString())));
        JsonNode hydBefore = body(mvc.perform(get("/api/v1/rooms/{id}/quote", hyderabadRoom).param("checkIn", in.toString())
                .param("checkOut", in.plusDays(1).toString())));

        String policy = """
                {"weekendDays":["FRIDAY","SATURDAY"],"weekendMultiplier":1.15,"minPriceFactor":0.8,"maxPriceFactor":1.8,
                 "serviceFeeRate":0.10,"taxRate":0.05,"seasons":[],"demandTiers":[{"minOccupancy":0,"multiplier":1.0}],
                 "notes":"Dubai: 10% service fee, 5% VAT"}""";
        mvc.perform(post("/api/v1/admin/pricing/policies").param("propertyId", DXB).with(user("STAFF"))
                .contentType(MediaType.APPLICATION_JSON).content(policy)).andExpect(status().isForbidden());
        JsonNode published = body(mvc.perform(post("/api/v1/admin/pricing/policies").param("propertyId", DXB)
                .with(user("ADMIN")).contentType(MediaType.APPLICATION_JSON).content(policy)).andExpect(status().isCreated()));
        assertThat(published.get("propertyId").asText()).isEqualTo(DXB);
        assertThat(published.get("version").asInt()).isEqualTo(dxbBefore.get("quote").get("pricingPolicyVersion").asInt() + 1);

        JsonNode dxbAfter = body(mvc.perform(get("/api/v1/rooms/{id}/quote", dubaiRoom).param("checkIn", in.toString())
                .param("checkOut", in.plusDays(1).toString())));
        JsonNode hydAfter = body(mvc.perform(get("/api/v1/rooms/{id}/quote", hyderabadRoom).param("checkIn", in.toString())
                .param("checkOut", in.plusDays(1).toString())));
        assertThat(dxbAfter.get("quote").get("pricingPolicyVersion").asInt()).isEqualTo(published.get("version").asInt());
        assertThat(dxbAfter.get("quote").get("serviceFee").decimalValue())
                .isEqualByComparingTo(dxbAfter.get("quote").get("subtotal").decimalValue().multiply(new java.math.BigDecimal("0.10"))
                        .setScale(2, java.math.RoundingMode.HALF_UP));
        assertThat(dxbAfter.get("quote").get("total")).isNotEqualTo(dxbBefore.get("quote").get("total"));
        // the other property's prices and policy version did not change
        assertThat(hydAfter.get("quote").get("total")).isEqualTo(hydBefore.get("quote").get("total"));
        assertThat(hydAfter.get("quote").get("pricingPolicyVersion")).isEqualTo(hydBefore.get("quote").get("pricingPolicyVersion"));
        mvc.perform(get("/api/v1/admin/pricing/policies").param("propertyId", HYD).with(user("STAFF")))
                .andExpect(jsonPath("$[0].propertyId").value(HYD));
    }

    @Test
    void administratorsCreatePropertiesWithAnInitialPolicyAndRoomsStayInTheirProperty() throws Exception {
        String request = """
                {"code":"BLR","name":"GrandVista Bengaluru","city":"Bengaluru","country":"IN","timeZone":"Asia/Kolkata"}""";
        mvc.perform(post("/api/v1/admin/properties").with(user("STAFF")).contentType(MediaType.APPLICATION_JSON)
                .content(request)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/properties").with(user("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content(request.replace("Asia/Kolkata", "Mars/Olympus")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_TIME_ZONE"));
        JsonNode created = body(mvc.perform(post("/api/v1/admin/properties").with(user("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isCreated()));
        String blr = created.get("id").asText();
        mvc.perform(post("/api/v1/admin/properties").with(user("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                .content(request)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROPERTY_CODE_TAKEN"));
        mvc.perform(get("/api/v1/admin/pricing/policy").param("propertyId", blr).with(user("STAFF")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.propertyId").value(blr));

        JsonNode type = body(mvc.perform(post("/api/v1/admin/room-types").with(user("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"propertyId\":\"" + blr + "\",\"code\":\"CLASSIC\",\"name\":\"Classic\",\"sortOrder\":1}"))
                .andExpect(status().isCreated()));
        assertThat(type.get("propertyId").asText()).isEqualTo(blr);
        String roomBody = """
                {"roomNumber":"101","roomTypeId":"%s","name":"Bengaluru Classic","maxOccupancy":2,
                 "bedConfiguration":"1 Queen bed","basePrice":3900.00,"currency":"INR","amenityCodes":["WIFI"]%s}""";
        JsonNode room = body(mvc.perform(post("/api/v1/admin/rooms").with(user("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                .content(roomBody.formatted(type.get("id").asText(), ""))).andExpect(status().isCreated()));
        assertThat(room.get("property").get("code").asText()).isEqualTo("BLR");

        // a room cannot be moved onto a room type of another property
        String goaType = body(mvc.perform(get("/api/v1/room-types").param("propertyId", GOA))).get(0).get("id").asText();
        mvc.perform(put("/api/v1/admin/rooms/{id}", room.get("id").asText()).with(user("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(roomBody.formatted(goaType, ",\"version\":" + room.get("version").asLong())))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("ROOM_TYPE_OTHER_PROPERTY"));

        // an inactive property disappears from search and refuses new holds
        mvc.perform(put("/api/v1/admin/properties/{id}", blr).with(user("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                .content(request.replace("}", ",\"active\":false}"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        mvc.perform(get("/api/v1/rooms").param("city", "Bengaluru")).andExpect(jsonPath("$.totalElements").value(0));
        LocalDate in = weekday();
        mvc.perform(post("/internal/v1/holds").with(serviceToken()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bookingId":"%s","roomId":"%s","checkIn":"%s","checkOut":"%s","guests":1}
                                """.formatted(UUID.randomUUID(), room.get("id").asText(), in, in.plusDays(1))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("PROPERTY_INACTIVE"));
    }

    @Test
    void holdsCarryTheirPropertyAndInventoryViewsFilterByProperty() throws Exception {
        LocalDate in = weekday().plusWeeks(2);
        String goaRoom = body(mvc.perform(get("/api/v1/rooms").param("propertyId", GOA).param("size", "1")))
                .get("content").get(0).get("id").asText();
        JsonNode hold = body(mvc.perform(post("/internal/v1/holds").with(serviceToken()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"bookingId":"%s","roomId":"%s","checkIn":"%s","checkOut":"%s","guests":1}
                        """.formatted(UUID.randomUUID(), goaRoom, in, in.plusDays(2)))).andExpect(status().isCreated()));
        assertThat(hold.get("propertyId").asText()).isEqualTo(GOA);
        assertThat(hold.get("propertyName").asText()).isEqualTo("GrandVista Goa");
        assertThat(hold.get("propertyTimeZone").asText()).isEqualTo("Asia/Kolkata");

        mvc.perform(get("/api/v1/admin/inventory/summary").param("propertyId", GOA).with(user("STAFF")))
                .andExpect(jsonPath("$.totalRooms").value(4));
        JsonNode goaAllocations = body(mvc.perform(get("/api/v1/admin/inventory/allocations").param("propertyId", GOA)
                .with(user("STAFF"))));
        assertThat(values(goaAllocations.get("content"), "/roomId")).contains(goaRoom);
        JsonNode dxbAllocations = body(mvc.perform(get("/api/v1/admin/inventory/allocations").param("propertyId", DXB)
                .with(user("STAFF"))));
        assertThat(values(dxbAllocations.get("content"), "/roomId")).doesNotContain(goaRoom);
        JsonNode goaRooms = body(mvc.perform(get("/api/v1/admin/rooms").param("propertyId", GOA).with(user("STAFF"))));
        assertThat(goaRooms.get("totalElements").asLong()).isEqualTo(4);
    }
}
