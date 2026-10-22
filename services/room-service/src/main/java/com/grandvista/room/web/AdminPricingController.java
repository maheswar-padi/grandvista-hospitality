package com.grandvista.room.web;

import com.grandvista.platform.security.AuthenticatedUser;
import com.grandvista.room.service.PricingPolicyService;
import com.grandvista.room.web.dto.PricingDtos.PolicyRequest;
import com.grandvista.room.web.dto.PricingDtos.PolicyResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
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

    public AdminPricingController(PricingPolicyService policies) {
        this.policies = policies;
    }

    @Operation(summary = "Active pricing policy (STAFF, ADMIN)")
    @GetMapping("/policy")
    public PolicyResponse active() {
        return PolicyResponse.from(policies.active());
    }

    @Operation(summary = "All policy versions, newest first (STAFF, ADMIN)")
    @GetMapping("/policies")
    public List<PolicyResponse> history() {
        return policies.history().stream().map(PolicyResponse::from).toList();
    }

    @Operation(summary = "Publish a new policy version; the previous one is archived (ADMIN)")
    @PostMapping("/policies")
    @ResponseStatus(HttpStatus.CREATED)
    public PolicyResponse publish(@Valid @RequestBody PolicyRequest request, Authentication authentication) {
        AuthenticatedUser user = AuthenticatedUser.from(authentication);
        return PolicyResponse.from(policies.publish(request, "user:" + user.requireUserId()));
    }
}
