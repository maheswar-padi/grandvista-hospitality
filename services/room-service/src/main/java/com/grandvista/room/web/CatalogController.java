package com.grandvista.room.web;

import com.grandvista.room.repository.AmenityRepository;
import com.grandvista.room.repository.RoomTypeRepository;
import com.grandvista.room.web.dto.RoomDtos.AmenityResponse;
import com.grandvista.room.web.dto.RoomDtos.RoomTypeResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Catalogue")
public class CatalogController {

    private final RoomTypeRepository roomTypes;
    private final AmenityRepository amenities;

    public CatalogController(RoomTypeRepository roomTypes, AmenityRepository amenities) {
        this.roomTypes = roomTypes;
        this.amenities = amenities;
    }

    @Operation(summary = "Room types")
    @GetMapping("/room-types")
    @Transactional(readOnly = true)
    public List<RoomTypeResponse> roomTypes(@org.springframework.web.bind.annotation.RequestParam(required = false)
            java.util.UUID propertyId) {
        var list = propertyId == null ? roomTypes.findAllByOrderBySortOrderAscNameAsc()
                : roomTypes.findAllByPropertyIdOrderBySortOrderAscNameAsc(propertyId);
        return list.stream().map(RoomTypeResponse::from).toList();
    }

    @Operation(summary = "Amenities")
    @GetMapping("/amenities")
    @Transactional(readOnly = true)
    public List<AmenityResponse> amenities() {
        return amenities.findAllByOrderByCategoryAscNameAsc().stream().map(AmenityResponse::from).toList();
    }
}
