package com.grandvista.room.web;

import com.grandvista.platform.web.PageResponse;
import com.grandvista.room.service.RoomQueryService;
import com.grandvista.room.service.RoomQueryService.SearchCriteria;
import com.grandvista.room.service.RoomQueryService.SortOrder;
import com.grandvista.room.web.dto.RoomDtos.AvailabilityResponse;
import com.grandvista.room.web.dto.RoomDtos.CalendarResponse;
import com.grandvista.room.web.dto.RoomDtos.QuoteResponse;
import com.grandvista.room.web.dto.RoomDtos.RoomResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rooms")
@Validated
@Tag(name = "Rooms", description = "Public catalogue, availability and quotes")
public class RoomController {

    private final RoomQueryService queries;

    public RoomController(RoomQueryService queries) {
        this.queries = queries;
    }

    @Operation(summary = "Search rooms; with checkIn/checkOut only rooms free for the whole stay are returned")
    @GetMapping
    public PageResponse<RoomResponse> search(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkIn,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOut,
            @RequestParam(required = false) @Min(1) @Max(12) Integer guests,
            @RequestParam(required = false) @Pattern(regexp = "^[A-Z_]{2,40}$") String roomType,
            @RequestParam(required = false) @DecimalMin("0") BigDecimal minPrice,
            @RequestParam(required = false) @DecimalMin("0") BigDecimal maxPrice,
            @RequestParam(required = false) @Size(max = 10) Set<@Pattern(regexp = "^[A-Z_]{2,40}$") String> amenities,
            @RequestParam(defaultValue = "RECOMMENDED") SortOrder sort,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000) int page,
            @RequestParam(defaultValue = "12") @Min(1) @Max(50) int size) {
        return queries.search(new SearchCriteria(checkIn, checkOut, guests, roomType, minPrice, maxPrice, amenities,
                sort, page, size));
    }

    @Operation(summary = "Room details")
    @GetMapping("/{roomId}")
    public RoomResponse get(@PathVariable UUID roomId) {
        return queries.get(roomId, false);
    }

    @Operation(summary = "Is the room free for the whole stay [checkIn, checkOut)?")
    @GetMapping("/{roomId}/availability")
    public AvailabilityResponse availability(@PathVariable UUID roomId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkIn,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOut) {
        return queries.availability(roomId, checkIn, checkOut);
    }

    @Operation(summary = "Unavailable nights in a window (max 120 days), for date pickers")
    @GetMapping("/{roomId}/calendar")
    public CalendarResponse calendar(@PathVariable UUID roomId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(defaultValue = "60") int days) {
        return queries.calendar(roomId, from, days);
    }

    @Operation(summary = "Itemised dynamic-price quote for a stay (informational; the booking re-prices authoritatively)")
    @GetMapping("/{roomId}/quote")
    public QuoteResponse quote(@PathVariable UUID roomId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkIn,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOut,
            @RequestParam(defaultValue = "1") @Min(1) @Max(12) int guests) {
        return queries.quote(roomId, checkIn, checkOut, guests);
    }
}
