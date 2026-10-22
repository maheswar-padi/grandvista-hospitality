package com.grandvista.room.web;

import com.grandvista.platform.persistence.Specs;
import com.grandvista.platform.web.PageResponse;
import com.grandvista.room.domain.Room;
import com.grandvista.room.domain.RoomStatus;
import com.grandvista.room.repository.RoomRepository;
import com.grandvista.room.service.RoomCatalogService;
import com.grandvista.room.service.RoomQueryService;
import com.grandvista.room.web.dto.RoomDtos.AmenityRequest;
import com.grandvista.room.web.dto.RoomDtos.AmenityResponse;
import com.grandvista.room.web.dto.RoomDtos.ImageResponse;
import com.grandvista.room.web.dto.RoomDtos.RoomRequest;
import com.grandvista.room.web.dto.RoomDtos.RoomResponse;
import com.grandvista.room.web.dto.RoomDtos.RoomTypeRequest;
import com.grandvista.room.web.dto.RoomDtos.RoomTypeResponse;
import com.grandvista.room.web.dto.RoomDtos.StatusChangeRequest;
import com.grandvista.room.web.dto.RoomDtos.StatusChangeResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/admin")
@Validated
@Tag(name = "Room administration")
@SecurityRequirement(name = "bearer-jwt")
public class AdminRoomController {

    private final RoomCatalogService catalog;
    private final RoomQueryService queries;
    private final RoomRepository rooms;

    public AdminRoomController(RoomCatalogService catalog, RoomQueryService queries, RoomRepository rooms) {
        this.catalog = catalog;
        this.queries = queries;
        this.rooms = rooms;
    }

    @Operation(summary = "List all rooms in any status (STAFF, ADMIN)")
    @GetMapping("/rooms")
    @Transactional(readOnly = true)
    public PageResponse<RoomResponse> list(@RequestParam(required = false) RoomStatus status,
            @RequestParam(required = false) @Size(max = 60) String q,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        Specification<Room> filter = Specs.allOf(Specs.eq("status", status), Specs.containsIgnoreCase(q, "name", "roomNumber"));
        var result = rooms.findAll(filter, PageRequest.of(page, size, Sort.by("roomNumber")));
        // one batched load for the page (rooms, amenities, images) instead of a query per room
        List<RoomResponse> content = queries.toResponses(result.getContent().stream().map(Room::getId).toList(), null);
        return new PageResponse<>(content, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Operation(summary = "Room details in any status (STAFF, ADMIN)")
    @GetMapping("/rooms/{roomId}")
    public RoomResponse get(@PathVariable UUID roomId) {
        return queries.get(roomId, true);
    }

    @Operation(summary = "Create a room (ADMIN)")
    @PostMapping("/rooms")
    @ResponseStatus(HttpStatus.CREATED)
    public RoomResponse create(@Valid @RequestBody RoomRequest request) {
        Room room = catalog.create(request);
        return queries.get(room.getId(), true);
    }

    @Operation(summary = "Update room details and base price (ADMIN). Requires the current version.")
    @PutMapping("/rooms/{roomId}")
    public RoomResponse update(@PathVariable UUID roomId, @Valid @RequestBody RoomRequest request) {
        catalog.update(roomId, request);
        return queries.get(roomId, true);
    }

    @Operation(summary = "Change operational status: ACTIVE, MAINTENANCE, OUT_OF_SERVICE, INACTIVE (STAFF, ADMIN)")
    @PutMapping("/rooms/{roomId}/status")
    public StatusChangeResponse changeStatus(@PathVariable UUID roomId, @Valid @RequestBody StatusChangeRequest request) {
        RoomCatalogService.StatusChange change = catalog.changeStatus(roomId, request.status(), request.reason());
        String warning = change.futureConfirmedStays() > 0 && !request.status().isSellable()
                ? change.futureConfirmedStays() + " upcoming confirmed stay(s) remain on this room and are unaffected."
                : null;
        return new StatusChangeResponse(queries.get(roomId, true), change.futureConfirmedStays(), warning);
    }

    @Operation(summary = "Upload a room photograph: JPEG, PNG or WebP up to 5 MB (ADMIN)")
    @PostMapping(path = "/rooms/{roomId}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ImageResponse uploadImage(@PathVariable UUID roomId, @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) @Size(max = 200) String altText) throws IOException {
        return ImageResponse.from(catalog.addImage(roomId, file.getBytes(), altText));
    }

    @Operation(summary = "Delete a room photograph (ADMIN)")
    @DeleteMapping("/rooms/{roomId}/images/{imageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteImage(@PathVariable UUID roomId, @PathVariable UUID imageId) {
        catalog.removeImage(roomId, imageId);
    }

    @Operation(summary = "Create a room type (ADMIN)")
    @PostMapping("/room-types")
    @ResponseStatus(HttpStatus.CREATED)
    public RoomTypeResponse createType(@Valid @RequestBody RoomTypeRequest request) {
        return RoomTypeResponse.from(catalog.createType(request));
    }

    @Operation(summary = "Update a room type (ADMIN)")
    @PutMapping("/room-types/{id}")
    public RoomTypeResponse updateType(@PathVariable UUID id, @Valid @RequestBody RoomTypeRequest request) {
        return RoomTypeResponse.from(catalog.updateType(id, request));
    }

    @Operation(summary = "Create an amenity (ADMIN)")
    @PostMapping("/amenities")
    @ResponseStatus(HttpStatus.CREATED)
    public AmenityResponse createAmenity(@Valid @RequestBody AmenityRequest request) {
        return AmenityResponse.from(catalog.createAmenity(request));
    }

    @Operation(summary = "Update an amenity (ADMIN)")
    @PutMapping("/amenities/{id}")
    public AmenityResponse updateAmenity(@PathVariable UUID id, @Valid @RequestBody AmenityRequest request) {
        return AmenityResponse.from(catalog.updateAmenity(id, request));
    }
}
