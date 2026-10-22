package com.grandvista.room.service;

import com.grandvista.platform.error.ApiException;
import com.grandvista.room.domain.Amenity;
import com.grandvista.room.domain.InventoryEvent;
import com.grandvista.room.domain.Room;
import com.grandvista.room.domain.RoomImage;
import com.grandvista.room.domain.RoomStatus;
import com.grandvista.room.domain.RoomType;
import com.grandvista.room.media.ImageType;
import com.grandvista.room.media.MediaProperties;
import com.grandvista.room.media.MediaStorage;
import com.grandvista.room.repository.AmenityRepository;
import com.grandvista.room.repository.InventoryAllocationRepository;
import com.grandvista.room.repository.RoomRepository;
import com.grandvista.room.repository.RoomTypeRepository;
import com.grandvista.room.web.dto.RoomDtos.AmenityRequest;
import com.grandvista.room.web.dto.RoomDtos.RoomRequest;
import com.grandvista.room.web.dto.RoomDtos.RoomTypeRequest;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Administrative catalogue operations: rooms, room types, amenities, status and photographs. */
@Service
public class RoomCatalogService {

    private final RoomRepository rooms;
    private final RoomTypeRepository roomTypes;
    private final AmenityRepository amenities;
    private final InventoryAllocationRepository allocations;
    private final InventoryAuditService audit;
    private final MediaStorage media;
    private final MediaProperties mediaProperties;
    private final StayRules stayRules;
    private final Clock clock;

    public RoomCatalogService(RoomRepository rooms, RoomTypeRepository roomTypes, AmenityRepository amenities,
            InventoryAllocationRepository allocations, InventoryAuditService audit, MediaStorage media,
            MediaProperties mediaProperties, StayRules stayRules, Clock clock) {
        this.rooms = rooms;
        this.roomTypes = roomTypes;
        this.amenities = amenities;
        this.allocations = allocations;
        this.audit = audit;
        this.media = media;
        this.mediaProperties = mediaProperties;
        this.stayRules = stayRules;
        this.clock = clock;
    }

    @Transactional
    public Room create(RoomRequest request) {
        String number = request.roomNumber().trim().toUpperCase();
        if (rooms.existsByRoomNumber(number)) {
            throw ApiException.conflict("ROOM_NUMBER_TAKEN", "Room number " + number + " already exists.");
        }
        Room room = new Room(number, requireType(request.roomTypeId()), details(request), clock.instant());
        rooms.save(room);
        audit.record(InventoryEvent.Type.ROOM_CREATED, room.getId(), null, null, "room " + number);
        return room;
    }

    @Transactional
    public Room update(UUID roomId, RoomRequest request) {
        Room room = require(roomId);
        if (request.version() == null || request.version() != room.getVersion()) {
            throw new ObjectOptimisticLockingFailureException(Room.class, roomId);
        }
        String number = request.roomNumber().trim().toUpperCase();
        if (!number.equals(room.getRoomNumber())) {
            throw ApiException.unprocessable("ROOM_NUMBER_IMMUTABLE", "The room number cannot be changed.");
        }
        room.update(requireType(request.roomTypeId()), details(request), clock.instant());
        audit.record(InventoryEvent.Type.ROOM_UPDATED, room.getId(), null, null, "base price " + request.basePrice());
        return room;
    }

    public record StatusChange(Room room, long futureConfirmedStays) {
    }

    /**
     * Changes operational status under an exclusive row lock so it serialises with concurrent holds.
     * Existing confirmed stays are kept (guests have paid); the caller is told how many exist. Retiring a
     * room (INACTIVE) is refused while it still has upcoming confirmed stays.
     */
    @Transactional
    public StatusChange changeStatus(UUID roomId, RoomStatus status, String reason) {
        Room room = rooms.findByIdForUpdate(roomId)
                .orElseThrow(() -> ApiException.notFound("ROOM_NOT_FOUND", "Room not found."));
        long future = allocations.countFutureConfirmed(roomId, stayRules.today());
        if (status == RoomStatus.INACTIVE && future > 0) {
            throw ApiException.conflict("ROOM_HAS_UPCOMING_STAYS",
                    "The room has " + future + " upcoming confirmed stay(s); cancel or move them before retiring it.");
        }
        RoomStatus previous = room.getStatus();
        room.changeStatus(status, reason, clock.instant());
        audit.record(InventoryEvent.Type.ROOM_STATUS_CHANGED, roomId, null, null,
                previous + " -> " + status + (reason == null ? "" : " (" + reason + ")"));
        return new StatusChange(room, future);
    }

    @Transactional
    public RoomImage addImage(UUID roomId, byte[] content, String altText) {
        if (content == null || content.length == 0) {
            throw ApiException.badRequest("EMPTY_FILE", "The uploaded file is empty.");
        }
        if (content.length > mediaProperties.maxImageSize().toBytes()) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
                    "Images must be at most " + mediaProperties.maxImageSize().toMegabytes() + " MB.");
        }
        ImageType type = ImageType.detect(content).orElseThrow(() -> new ApiException(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_IMAGE_TYPE", "Only JPEG, PNG and WebP images are accepted."));
        Room room = require(roomId);
        if (room.getImages().size() >= Room.MAX_IMAGES) {
            throw ApiException.unprocessable("TOO_MANY_IMAGES", "A room can have at most " + Room.MAX_IMAGES + " images.");
        }
        MediaStorage.StoredMedia stored = media.store(content, type);
        // If the database transaction rolls back, do not leave an orphaned file behind.
        afterRollback(() -> media.delete(stored.key()));
        RoomImage image = room.addImage(stored.key(), type.mediaType(), stored.sizeBytes(), sanitize(altText), clock.instant());
        audit.record(InventoryEvent.Type.ROOM_IMAGE_ADDED, roomId, null, null, stored.key());
        return image;
    }

    @Transactional
    public void removeImage(UUID roomId, UUID imageId) {
        Room room = require(roomId);
        RoomImage image = room.removeImage(imageId, clock.instant())
                .orElseThrow(() -> ApiException.notFound("IMAGE_NOT_FOUND", "Image not found."));
        afterCommit(() -> media.delete(image.getStorageKey()));
        audit.record(InventoryEvent.Type.ROOM_IMAGE_REMOVED, roomId, null, null, image.getStorageKey());
    }

    @Transactional
    public RoomType createType(RoomTypeRequest request) {
        if (roomTypes.existsByCode(request.code())) {
            throw ApiException.conflict("ROOM_TYPE_CODE_TAKEN", "Room type code already exists.");
        }
        return roomTypes.save(new RoomType(request.code(), request.name(), request.description(), request.sortOrder(),
                clock.instant()));
    }

    @Transactional
    public RoomType updateType(UUID id, RoomTypeRequest request) {
        RoomType type = requireType(id);
        if (!type.getCode().equals(request.code())) {
            throw ApiException.unprocessable("ROOM_TYPE_CODE_IMMUTABLE", "The room type code cannot be changed.");
        }
        type.update(request.name(), request.description(), request.sortOrder(), clock.instant());
        return type;
    }

    @Transactional
    public Amenity createAmenity(AmenityRequest request) {
        if (amenities.existsByCode(request.code())) {
            throw ApiException.conflict("AMENITY_CODE_TAKEN", "Amenity code already exists.");
        }
        return amenities.save(new Amenity(request.code(), request.name(), request.icon(), request.category(), clock.instant()));
    }

    @Transactional
    public Amenity updateAmenity(UUID id, AmenityRequest request) {
        Amenity amenity = amenities.findById(id).orElseThrow(() -> ApiException.notFound("AMENITY_NOT_FOUND", "Amenity not found."));
        if (!amenity.getCode().equals(request.code())) {
            throw ApiException.unprocessable("AMENITY_CODE_IMMUTABLE", "The amenity code cannot be changed.");
        }
        amenity.update(request.name(), request.icon(), request.category());
        return amenity;
    }

    private Room.Details details(RoomRequest r) {
        Set<String> codes = r.amenityCodes();
        List<Amenity> found = codes.isEmpty() ? List.of() : amenities.findByCodeIn(codes);
        if (found.size() != codes.size()) {
            Set<String> known = found.stream().map(Amenity::getCode).collect(Collectors.toSet());
            throw ApiException.unprocessable("UNKNOWN_AMENITY", "Unknown amenity code(s): "
                    + codes.stream().filter(c -> !known.contains(c)).sorted().collect(Collectors.joining(", ")));
        }
        return new Room.Details(r.name().trim(), sanitize(r.description()), r.maxOccupancy().shortValue(),
                r.bedConfiguration().trim(), r.sizeSqM() == null ? null : r.sizeSqM().shortValue(),
                r.floor() == null ? null : r.floor().shortValue(), sanitize(r.viewType()), r.basePrice(), r.currency(), found);
    }

    private Room require(UUID roomId) {
        return rooms.findById(roomId).orElseThrow(() -> ApiException.notFound("ROOM_NOT_FOUND", "Room not found."));
    }

    private RoomType requireType(UUID id) {
        return roomTypes.findById(id).orElseThrow(() -> ApiException.unprocessable("UNKNOWN_ROOM_TYPE", "Unknown room type."));
    }

    private static String sanitize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void afterRollback(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    action.run();
                }
            }
        });
    }

    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
