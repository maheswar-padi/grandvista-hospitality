package com.grandvista.room.service;

import com.grandvista.platform.error.ApiException;
import com.grandvista.room.domain.InventoryEvent;
import com.grandvista.room.domain.PricingPolicy;
import com.grandvista.room.domain.Property;
import com.grandvista.room.domain.RoomStatus;
import com.grandvista.room.repository.PricingPolicyRepository;
import com.grandvista.room.repository.PropertyRepository;
import com.grandvista.room.repository.RoomRepository;
import com.grandvista.room.web.dto.PropertyDtos.PropertyRequest;
import com.grandvista.room.web.dto.PropertyDtos.PropertyResponse;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The hotel portfolio: lookup, defaults for single-property callers, and administration. */
@Service
public class PropertyService {

    /** The original hotel (created by migration V3); used when a caller does not name a property. */
    public static final UUID DEFAULT_PROPERTY_ID = UUID.fromString("d1000000-0000-4000-8000-000000000001");

    private final PropertyRepository properties;
    private final RoomRepository rooms;
    private final PricingPolicyRepository policies;
    private final InventoryAuditService audit;
    private final Clock clock;

    public PropertyService(PropertyRepository properties, RoomRepository rooms, PricingPolicyRepository policies,
            InventoryAuditService audit, Clock clock) {
        this.properties = properties;
        this.rooms = rooms;
        this.policies = policies;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<PropertyResponse> list(boolean includeInactive) {
        var list = includeInactive ? properties.findAllByOrderByNameAsc() : properties.findByActiveTrueOrderByNameAsc();
        return list.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public PropertyResponse get(UUID id, boolean includeInactive) {
        Property p = properties.findById(id).filter(x -> includeInactive || x.isActive()).orElseThrow(PropertyService::notFound);
        return toResponse(p);
    }

    /** The named property, or the default one when {@code id} is null. */
    @Transactional(readOnly = true)
    public Property resolve(UUID id) {
        if (id != null) {
            return properties.findById(id).orElseThrow(PropertyService::notFound);
        }
        return properties.findById(DEFAULT_PROPERTY_ID)
                .or(() -> properties.findAllByOrderByNameAsc().stream().findFirst())
                .orElseThrow(PropertyService::notFound);
    }

    /**
     * Creates a property. Its first pricing policy is a copy of the default property's active policy, so its rooms
     * can be priced immediately; administrators then publish property-specific versions.
     */
    @Transactional
    public PropertyResponse create(PropertyRequest request, String actor) {
        if (properties.existsByCode(request.code())) {
            throw ApiException.conflict("PROPERTY_CODE_TAKEN", "Property code " + request.code() + " already exists.");
        }
        var now = clock.instant();
        Property property = properties.save(new Property(null, request.code(), request.name().trim(), request.city().trim(),
                request.country(), zone(request.timeZone()), now));
        if (Boolean.FALSE.equals(request.active())) {
            property.update(property.getName(), property.getCity(), property.getCountry(), property.zoneId(), false, now);
        }
        policies.findActive(resolve(null).getId()).ifPresent(template -> policies.save(template.copyFor(property.getId(), 1,
                "Initial policy copied from the default property's version " + template.getVersion() + ".", actor, now)));
        audit.record(InventoryEvent.Type.PROPERTY_CREATED, null, null, null, property.getCode() + " " + property.getName());
        return toResponse(property);
    }

    @Transactional
    public PropertyResponse update(UUID id, PropertyRequest request) {
        Property property = properties.findById(id).orElseThrow(PropertyService::notFound);
        if (!property.getCode().equals(request.code())) {
            throw ApiException.unprocessable("PROPERTY_CODE_IMMUTABLE", "The property code cannot be changed.");
        }
        property.update(request.name().trim(), request.city().trim(), request.country(), zone(request.timeZone()),
                request.active() == null || request.active(), clock.instant());
        audit.record(InventoryEvent.Type.PROPERTY_UPDATED, null, null, null,
                property.getCode() + (property.isActive() ? " active" : " inactive"));
        return toResponse(property);
    }

    private PropertyResponse toResponse(Property p) {
        return PropertyResponse.from(p, rooms.countByPropertyId(p.getId()),
                rooms.countByPropertyIdAndStatus(p.getId(), RoomStatus.ACTIVE));
    }

    private static ZoneId zone(String id) {
        try {
            return ZoneId.of(id);
        } catch (DateTimeException ex) {
            throw ApiException.unprocessable("INVALID_TIME_ZONE", "Unknown time zone '" + id + "'. Use an IANA name such as Asia/Kolkata.");
        }
    }

    static ApiException notFound() {
        return ApiException.notFound("PROPERTY_NOT_FOUND", "Property not found.");
    }
}
