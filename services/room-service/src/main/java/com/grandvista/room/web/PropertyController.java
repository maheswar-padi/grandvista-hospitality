package com.grandvista.room.web;

import com.grandvista.platform.security.AuthenticatedUser;
import com.grandvista.room.service.PropertyService;
import com.grandvista.room.web.dto.PropertyDtos.PropertyRequest;
import com.grandvista.room.web.dto.PropertyDtos.PropertyResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Properties")
public class PropertyController {

    private final PropertyService properties;

    public PropertyController(PropertyService properties) {
        this.properties = properties;
    }

    @Operation(summary = "Active properties (hotels) of the portfolio")
    @GetMapping("/api/v1/properties")
    public List<PropertyResponse> list() {
        return properties.list(false);
    }

    @Operation(summary = "One active property")
    @GetMapping("/api/v1/properties/{id}")
    public PropertyResponse get(@PathVariable UUID id) {
        return properties.get(id, false);
    }

    @Operation(summary = "All properties, including inactive (STAFF, ADMIN)")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/api/v1/admin/properties")
    public List<PropertyResponse> adminList() {
        return properties.list(true);
    }

    @Operation(summary = "Create a property (ADMIN); its first pricing policy copies the default property's")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/api/v1/admin/properties")
    @ResponseStatus(HttpStatus.CREATED)
    public PropertyResponse create(@Valid @RequestBody PropertyRequest request, Authentication authentication) {
        return properties.create(request, "user:" + AuthenticatedUser.from(authentication).requireUserId());
    }

    @Operation(summary = "Update a property (ADMIN); the code cannot change")
    @SecurityRequirement(name = "bearerAuth")
    @PutMapping("/api/v1/admin/properties/{id}")
    public PropertyResponse update(@PathVariable UUID id, @Valid @RequestBody PropertyRequest request) {
        return properties.update(id, request);
    }
}
