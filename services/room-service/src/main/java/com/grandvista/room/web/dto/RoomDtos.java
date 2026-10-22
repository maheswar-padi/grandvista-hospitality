package com.grandvista.room.web.dto;

import com.grandvista.room.domain.Amenity;
import com.grandvista.room.domain.Room;
import com.grandvista.room.domain.RoomImage;
import com.grandvista.room.domain.RoomStatus;
import com.grandvista.room.domain.RoomType;
import com.grandvista.room.pricing.PriceQuote;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Public API contracts of the room catalogue. Entities never leave the service. */
public final class RoomDtos {

    private RoomDtos() {
    }

    public static final String MEDIA_PATH = "/api/v1/media/";

    public record RoomTypeResponse(UUID id, String code, String name, String description, int sortOrder) {
        public static RoomTypeResponse from(RoomType t) {
            return new RoomTypeResponse(t.getId(), t.getCode(), t.getName(), t.getDescription(), t.getSortOrder());
        }
    }

    public record AmenityResponse(UUID id, String code, String name, String icon, String category) {
        public static AmenityResponse from(Amenity a) {
            return new AmenityResponse(a.getId(), a.getCode(), a.getName(), a.getIcon(), a.getCategory());
        }
    }

    public record ImageResponse(UUID id, String url, String altText, String contentType, int sizeBytes) {
        public static ImageResponse from(RoomImage i) {
            return new ImageResponse(i.getId(), MEDIA_PATH + i.getStorageKey(), i.getAltText(), i.getContentType(),
                    i.getSizeBytes());
        }
    }

    /** Price for the searched stay, present only when dates were supplied. */
    public record StayPrice(int nights, BigDecimal averageNightlyRate, BigDecimal total, String currency,
                            int pricingPolicyVersion) {
        public static StayPrice from(PriceQuote q) {
            return new StayPrice(q.nights(), q.averageNightlyRate(), q.total(), q.currency(), q.pricingPolicyVersion());
        }
    }

    public record RoomResponse(UUID id, String roomNumber, String name, String description, RoomTypeResponse roomType,
                               int maxOccupancy, String bedConfiguration, Short sizeSqM, Short floor, String viewType,
                               BigDecimal basePrice, String currency, RoomStatus status, String statusReason,
                               List<AmenityResponse> amenities, List<ImageResponse> images, StayPrice stayPrice,
                               long version, Instant updatedAt) {

        public static RoomResponse from(Room r, List<RoomImage> images, StayPrice stayPrice) {
            return new RoomResponse(r.getId(), r.getRoomNumber(), r.getName(), r.getDescription(),
                    RoomTypeResponse.from(r.getRoomType()), r.getMaxOccupancy(), r.getBedConfiguration(), r.getSizeSqM(),
                    r.getFloor(), r.getViewType(), r.getBasePrice(), r.getCurrency(), r.getStatus(), r.getStatusReason(),
                    r.getAmenities().stream().sorted(Comparator.comparing(Amenity::getName)).map(AmenityResponse::from).toList(),
                    images.stream().map(ImageResponse::from).toList(), stayPrice, r.getVersion(), r.getUpdatedAt());
        }
    }

    public record AvailabilityResponse(UUID roomId, LocalDate checkIn, LocalDate checkOut, boolean available,
                                       String reason) {
    }

    public record CalendarResponse(UUID roomId, LocalDate from, LocalDate to, List<LocalDate> unavailableNights) {
    }

    public record QuoteResponse(UUID roomId, int guests, boolean available, String unavailableReason, PriceQuote quote) {
    }

    /** Admin create/update payload. Status is not part of it: status changes have their own endpoint. */
    public record RoomRequest(
            @NotBlank @Size(max = 20) @Pattern(regexp = "^[A-Za-z0-9-]+$", message = "may contain letters, digits and '-'")
            String roomNumber,
            @NotNull UUID roomTypeId,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 4000) String description,
            @NotNull @Min(1) @Max(12) Integer maxOccupancy,
            @NotBlank @Size(max = 120) String bedConfiguration,
            @Min(1) @Max(2000) Integer sizeSqM,
            @Min(-5) @Max(200) Integer floor,
            @Size(max = 40) String viewType,
            @NotNull @DecimalMin(value = "1.00") @DecimalMax(value = "10000000.00") @Digits(integer = 10, fraction = 2)
            BigDecimal basePrice,
            @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be an ISO-4217 code") String currency,
            @NotNull @Size(max = 30) Set<@Pattern(regexp = "^[A-Z_]{2,40}$") String> amenityCodes,
            /** Required on update for optimistic concurrency; ignored on create. */
            Long version) {
    }

    public record StatusChangeRequest(@NotNull RoomStatus status, @Size(max = 255) String reason) {
    }

    public record StatusChangeResponse(RoomResponse room, long futureConfirmedStays, String warning) {
    }

    public record RoomTypeRequest(
            @NotBlank @Pattern(regexp = "^[A-Z_]{2,40}$") String code,
            @NotBlank @Size(max = 80) String name,
            @Size(max = 1000) String description,
            @Min(0) @Max(1000) int sortOrder) {
    }

    public record AmenityRequest(
            @NotBlank @Pattern(regexp = "^[A-Z_]{2,40}$") String code,
            @NotBlank @Size(max = 80) String name,
            @Size(max = 40) @Pattern(regexp = "^[a-z0-9-]*$") String icon,
            @NotBlank @Pattern(regexp = "^[A-Z_]{2,40}$") String category) {
    }
}
