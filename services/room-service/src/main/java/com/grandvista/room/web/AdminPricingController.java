package com.grandvista.room.web;

import com.grandvista.platform.security.AuthenticatedUser;
import com.grandvista.room.service.PricingPolicyService;
import com.grandvista.room.web.dto.PricingDtos.PolicyRequest;
import com.grandvista.room.web.dto.PricingDtos.PolicyResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import com.grandvista.room.service.PropertyService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/pricing")
@Tag(name = "Pricing administration")
@SecurityRequirement(name = "bearer-jwt")
public class AdminPricingController {

    private final PricingPolicyService policies;
    private final PropertyService properties;

    public AdminPricingController(PricingPolicyService policies, PropertyService properties) {
        this.policies = policies;
        this.properties = properties;
    }

    @Operation(summary = "Active pricing policy of a property (STAFF, ADMIN); default property if none given")
    @GetMapping("/policy")
    public PolicyResponse active(@RequestParam(required = false) UUID propertyId) {
        return PolicyResponse.from(policies.active(properties.resolve(propertyId).getId()));
    }

    @Operation(summary = "All policy versions of a property, newest first (STAFF, ADMIN)")
    @GetMapping("/policies")
    public List<PolicyResponse> history(@RequestParam(required = false) UUID propertyId) {
        return policies.history(properties.resolve(propertyId).getId()).stream().map(PolicyResponse::from).toList();
    }

    @Operation(summary = "Publish a new policy version for a property; its previous one is archived (ADMIN)")
    @PostMapping("/policies")
    @ResponseStatus(HttpStatus.CREATED)
    public PolicyResponse publish(@RequestParam(required = false) UUID propertyId, @Valid @RequestBody PolicyRequest request,
            Authentication authentication) {
        AuthenticatedUser user = AuthenticatedUser.from(authentication);
        return PolicyResponse.from(policies.publish(properties.resolve(propertyId).getId(), request,
                "user:" + user.requireUserId()));
    }
}
