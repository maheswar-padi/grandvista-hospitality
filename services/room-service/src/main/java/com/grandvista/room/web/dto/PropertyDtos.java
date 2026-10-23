package com.grandvista.room.web.dto;

import com.grandvista.room.domain.Property;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** Contracts of the property (hotel) portfolio. */
public final class PropertyDtos {

    private PropertyDtos() {
    }

    /** Embedded in room, room-type and hold responses. */
    public record PropertySummary(UUID id, String code, String name, String city, String country, String timeZone) {
        public static PropertySummary from(Property p) {
            return new PropertySummary(p.getId(), p.getCode(), p.getName(), p.getCity(), p.getCountry(), p.getTimeZone());
        }
    }

    public record PropertyResponse(UUID id, String code, String name, String city, String country, String timeZone,
                                   boolean active, long totalRooms, long activeRooms, Instant createdAt, long version) {
        public static PropertyResponse from(Property p, long totalRooms, long activeRooms) {
            return new PropertyResponse(p.getId(), p.getCode(), p.getName(), p.getCity(), p.getCountry(), p.getTimeZone(),
                    p.isActive(), totalRooms, activeRooms, p.getCreatedAt(), p.getVersion());
        }
    }

    /** Admin create/update. The code is fixed after creation. */
    public record PropertyRequest(
            @NotBlank @Pattern(regexp = "^[A-Z0-9]{2,10}$", message = "2–10 capital letters or digits") String code,
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 80) String city,
            @NotBlank @Pattern(regexp = "^[A-Z]{2}$", message = "must be an ISO 3166-1 alpha-2 country code") String country,
            @NotBlank @Size(max = 60) String timeZone,
            Boolean active) {
    }
}
