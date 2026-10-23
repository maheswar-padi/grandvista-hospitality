package com.grandvista.room.web;

import com.grandvista.platform.web.PageResponse;
import com.grandvista.room.domain.AllocationStatus;
import com.grandvista.room.service.AdminInventoryService;
import com.grandvista.room.web.dto.InventoryDtos.AllocationResponse;
import com.grandvista.room.web.dto.InventoryDtos.InventoryEventResponse;
import com.grandvista.room.web.dto.InventoryDtos.InventorySummary;
import com.grandvista.room.web.dto.InventoryDtos.OccupancyPoint;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/inventory")
@Validated
@Tag(name = "Inventory administration")
@SecurityRequirement(name = "bearer-jwt")
public class AdminInventoryController {

    private final AdminInventoryService inventory;

    public AdminInventoryController(AdminInventoryService inventory) {
        this.inventory = inventory;
    }

    @Operation(summary = "Room counts by status, today's occupancy, arrivals and departures (STAFF, ADMIN)")
    @GetMapping("/summary")
    public InventorySummary summary(@RequestParam(required = false) UUID propertyId) {
        return inventory.summary(propertyId);
    }

    @Operation(summary = "Hotel-wide occupancy per night in [from, to) (STAFF, ADMIN)")
    @GetMapping("/occupancy")
    public List<OccupancyPoint> occupancy(@RequestParam(required = false) UUID propertyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return inventory.occupancy(propertyId, from, to);
    }

    @Operation(summary = "Inventory allocations overlapping [from, to) (STAFF, ADMIN)")
    @GetMapping("/allocations")
    public PageResponse<AllocationResponse> allocations(@RequestParam(required = false) UUID propertyId,
            @RequestParam(required = false) UUID roomId,
            @RequestParam(required = false) Set<AllocationStatus> status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return inventory.allocations(propertyId, roomId, status, from, to, page, size);
    }

    @Operation(summary = "Inventory audit trail (STAFF, ADMIN)")
    @GetMapping("/events")
    public PageResponse<InventoryEventResponse> events(@RequestParam(required = false) UUID roomId,
            @RequestParam(required = false) UUID bookingId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return inventory.events(roomId, bookingId, page, size);
    }
}
