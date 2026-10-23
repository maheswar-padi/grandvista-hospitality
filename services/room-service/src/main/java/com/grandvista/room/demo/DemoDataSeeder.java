package com.grandvista.room.demo;

import com.grandvista.room.domain.Amenity;
import com.grandvista.room.domain.Room;
import com.grandvista.room.domain.RoomType;
import com.grandvista.room.media.ImageType;
import com.grandvista.room.media.MediaStorage;
import com.grandvista.room.domain.Property;
import com.grandvista.room.repository.AmenityRepository;
import com.grandvista.room.repository.PricingPolicyRepository;
import com.grandvista.room.repository.PropertyRepository;
import com.grandvista.room.service.PropertyService;
import com.grandvista.room.repository.RoomRepository;
import com.grandvista.room.repository.RoomTypeRepository;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;
import java.time.ZoneId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * DEMONSTRATION DATA. Creates a small, fictional room inventory the first time the service starts with
 * an empty catalogue and {@code gv.demo.seed-enabled=true}. Never runs when rooms already exist, so it
 * cannot overwrite real data. Disabled in the {@code prod} profile.
 */
@Component
@ConditionalOnProperty(name = "gv.demo.seed-enabled", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final String CURRENCY = "INR";

    private record DemoRoom(String number, String type, String name, String description, int occupancy, String beds,
                            int size, int floor, String view, String price, List<String> amenities, String image) {
    }

    private static final List<String> CORE = List.of("WIFI", "AIR_CONDITIONING", "SMART_TV", "COFFEE_MACHINE", "RAIN_SHOWER");

    private static final List<DemoRoom> ROOMS = List.of(
            new DemoRoom("101", "CLASSIC", "Classic Queen Room",
                    "A calm, well-proportioned room with a queen bed, blackout curtains and a compact work desk.",
                    2, "1 Queen bed", 24, 1, "Courtyard", "4500.00", plus("WORKSPACE"), "classic"),
            new DemoRoom("102", "CLASSIC", "Classic Twin Room",
                    "Two single beds, soft lighting and everything needed for a short city stay.",
                    2, "2 Single beds", 24, 1, "Courtyard", "4500.00", plus("WORKSPACE"), "classic-twin"),
            new DemoRoom("103", "CLASSIC", "Accessible Classic Room",
                    "Step-free access, a roll-in shower and wider doorways throughout.",
                    2, "1 Queen bed", 28, 1, "Courtyard", "4800.00", plus("ACCESSIBLE", "WORKSPACE"), "classic"),
            new DemoRoom("201", "DELUXE", "Deluxe King, City View",
                    "Floor-to-ceiling windows over the skyline, a king bed and a generous marble bathroom.",
                    3, "1 King bed", 32, 2, "City", "7200.00", plus("CITY_VIEW", "MINIBAR", "WORKSPACE", "BATHTUB"), "deluxe-city"),
            new DemoRoom("202", "DELUXE", "Deluxe King, Sea View",
                    "Wake up to the sea from a private balcony; king bed and deep soaking tub.",
                    3, "1 King bed", 32, 2, "Sea", "8400.00", plus("SEA_VIEW", "BALCONY", "MINIBAR", "BATHTUB"), "deluxe-sea"),
            new DemoRoom("203", "DELUXE", "Deluxe Twin, Sea View",
                    "Two double beds facing the water, ideal for friends travelling together.",
                    4, "2 Double beds", 34, 2, "Sea", "8800.00", plus("SEA_VIEW", "BALCONY", "MINIBAR"), "deluxe-sea"),
            new DemoRoom("301", "FAMILY", "Family Garden Room",
                    "Garden-level room with a king bed, two singles and space for little ones to play.",
                    4, "1 King bed + 2 Single beds", 42, 3, "Garden", "9800.00", plus("KITCHENETTE", "BATHTUB"), "family"),
            new DemoRoom("302", "FAMILY", "Family Connecting Rooms",
                    "Two connecting rooms sharing a lounge, sleeping up to five guests.",
                    5, "1 King bed + 3 Single beds", 55, 3, "City", "12500.00", plus("KITCHENETTE", "CITY_VIEW"), "family"),
            new DemoRoom("401", "SUITE", "Junior Suite",
                    "Open-plan suite with a sitting area, king bed and evening turndown service.",
                    3, "1 King bed + sofa bed", 52, 4, "City", "15500.00",
                    plus("CITY_VIEW", "MINIBAR", "BATHTUB", "ROOM_SERVICE", "WORKSPACE"), "suite"),
            new DemoRoom("402", "SUITE", "Ocean Suite",
                    "Separate living room, wrap-around balcony and panoramic sea views.",
                    3, "1 King bed + sofa bed", 68, 4, "Sea", "19500.00",
                    plus("SEA_VIEW", "BALCONY", "MINIBAR", "BATHTUB", "ROOM_SERVICE"), "suite-ocean"),
            new DemoRoom("501", "PRESIDENTIAL", "Presidential Suite",
                    "Top-floor residence with dining room, private terrace and dedicated butler.",
                    4, "1 King bed + 1 Queen bed", 140, 5, "Sea", "42000.00",
                    plus("SEA_VIEW", "BALCONY", "MINIBAR", "BATHTUB", "ROOM_SERVICE", "BUTLER", "KITCHENETTE", "WORKSPACE"),
                    "presidential"));

    private static List<String> plus(String... extra) {
        return java.util.stream.Stream.concat(CORE.stream(), java.util.Arrays.stream(extra)).distinct().toList();
    }

    /** Demo portfolio: the original hotel (created by migration V3) plus two more in other cities. */
    private record DemoProperty(UUID id, String code, String name, String city, String country, String zone,
                                List<String> roomNumbers) {
    }

    private static final List<DemoProperty> PROPERTIES = List.of(
            new DemoProperty(PropertyService.DEFAULT_PROPERTY_ID, "HYD", "GrandVista Hyderabad", "Hyderabad", "IN",
                    "Asia/Kolkata", ROOMS.stream().map(DemoRoom::number).toList()),
            new DemoProperty(UUID.fromString("d1000000-0000-4000-8000-000000000002"), "GOA", "GrandVista Goa", "Goa", "IN",
                    "Asia/Kolkata", List.of("101", "202", "301", "402")),
            new DemoProperty(UUID.fromString("d1000000-0000-4000-8000-000000000003"), "DXB", "GrandVista Dubai", "Dubai", "AE",
                    "Asia/Dubai", List.of("102", "201", "401", "501")));

    private final RoomRepository rooms;
    private final RoomTypeRepository roomTypes;
    private final AmenityRepository amenities;
    private final PropertyRepository properties;
    private final PricingPolicyRepository policies;
    private final MediaStorage media;
    private final Clock clock;

    public DemoDataSeeder(RoomRepository rooms, RoomTypeRepository roomTypes, AmenityRepository amenities,
            PropertyRepository properties, PricingPolicyRepository policies, MediaStorage media, Clock clock) {
        this.rooms = rooms;
        this.roomTypes = roomTypes;
        this.amenities = amenities;
        this.properties = properties;
        this.policies = policies;
        this.media = media;
        this.clock = clock;
    }

    /**
     * Idempotent per property: creates a missing demo property with room types and an initial pricing policy
     * copied from the original hotel, and seeds rooms only into a property that has none.
     */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Instant now = clock.instant();
        Property template = properties.findById(PropertyService.DEFAULT_PROPERTY_ID).orElse(null);
        if (template == null) {
            log.warn("DEMO DATA: default property missing; nothing seeded.");
            return;
        }
        List<RoomType> templateTypes = roomTypes.findAllByPropertyIdOrderBySortOrderAscNameAsc(template.getId());
        Map<String, Amenity> amenityByCode = amenities.findAll().stream()
                .collect(Collectors.toMap(Amenity::getCode, Function.identity()));
        Map<String, DemoRoom> roomByNumber = ROOMS.stream().collect(Collectors.toMap(DemoRoom::number, Function.identity()));
        int createdRooms = 0;
        int images = 0;
        for (DemoProperty dp : PROPERTIES) {
            Property property = properties.findById(dp.id()).orElseGet(() -> properties.save(new Property(dp.id(), dp.code(),
                    dp.name(), dp.city(), dp.country(), ZoneId.of(dp.zone()), now)));
            if (roomTypes.findAllByPropertyIdOrderBySortOrderAscNameAsc(property.getId()).isEmpty()) {
                templateTypes.forEach(t -> roomTypes.save(new RoomType(property, t.getCode(), t.getName(), t.getDescription(),
                        t.getSortOrder(), now)));
                roomTypes.flush();
            }
            if (policies.findActive(property.getId()).isEmpty()) {
                policies.findActive(template.getId()).ifPresent(p -> policies.save(p.copyFor(property.getId(), 1,
                        "Demo: initial policy copied from " + template.getName() + ".", "system", now)));
            }
            if (rooms.countByPropertyId(property.getId()) > 0) {
                continue;
            }
            Map<String, RoomType> types = roomTypes.findAllByPropertyIdOrderBySortOrderAscNameAsc(property.getId()).stream()
                    .collect(Collectors.toMap(RoomType::getCode, Function.identity()));
            for (String number : dp.roomNumbers()) {
                DemoRoom d = roomByNumber.get(number);
                List<Amenity> roomAmenities = d.amenities().stream().map(amenityByCode::get).toList();
                Room room = new Room(d.number(), types.get(d.type()), new Room.Details(d.name(), d.description(),
                        (short) d.occupancy(), d.beds(), (short) d.size(), (short) d.floor(), d.view(),
                        new BigDecimal(d.price()), CURRENCY, roomAmenities), now);
                images += attachImage(room, d);
                rooms.save(room);
                createdRooms++;
            }
        }
        if (createdRooms > 0) {
            log.info("DEMO DATA: {} properties, created {} fictional rooms ({} illustrative images). "
                    + "Disable with GV_DEMO_SEED_ENABLED=false.", PROPERTIES.size(), createdRooms, images);
        }
    }

    private int attachImage(Room room, DemoRoom d) {
        ClassPathResource resource = new ClassPathResource("demo-media/" + d.image() + ".webp");
        if (!resource.exists()) {
            return 0;
        }
        try (InputStream in = resource.getInputStream()) {
            byte[] bytes = in.readAllBytes();
            ImageType type = ImageType.detect(bytes).orElse(null);
            if (type == null) {
                return 0;
            }
            MediaStorage.StoredMedia stored = media.store(bytes, type);
            room.addImage(stored.key(), type.mediaType(), stored.sizeBytes(),
                    "Illustration of the " + d.name() + " (demo artwork, not a photograph)", clock.instant());
            return 1;
        } catch (IOException ex) {
            log.warn("Could not load demo image {}: {}", d.image(), ex.getMessage());
            return 0;
        }
    }
}
