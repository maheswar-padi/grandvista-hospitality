package com.grandvista.auth.web;

import com.grandvista.auth.service.AccountService;
import com.grandvista.auth.web.dto.AuthDtos.UpdateRolesRequest;
import com.grandvista.auth.web.dto.AuthDtos.UpdateStatusRequest;
import com.grandvista.auth.web.dto.AuthDtos.UserResponse;
import com.grandvista.platform.security.AuthenticatedUser;
import com.grandvista.platform.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/users")
@Validated
@Tag(name = "User administration")
@SecurityRequirement(name = "bearer-jwt")
public class AdminUserController {

    private final AccountService accounts;

    public AdminUserController(AccountService accounts) {
        this.accounts = accounts;
    }

    @Operation(summary = "List users (ADMIN)")
    @GetMapping
    public PageResponse<UserResponse> list(@RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PageResponse.of(accounts.search(q, PageRequest.of(page, size, Sort.by("createdAt").descending())),
                UserResponse::from);
    }

    @Operation(summary = "Get a user (ADMIN)")
    @GetMapping("/{userId}")
    public UserResponse get(@PathVariable UUID userId) {
        return UserResponse.from(accounts.get(userId));
    }

    @Operation(summary = "Replace a user's roles (ADMIN). Revokes the user's sessions.")
    @PutMapping("/{userId}/roles")
    public UserResponse updateRoles(@PathVariable UUID userId, @Valid @RequestBody UpdateRolesRequest request,
            Authentication authentication) {
        UUID actor = AuthenticatedUser.from(authentication).requireUserId();
        return UserResponse.from(accounts.updateRoles(actor, userId, request.roles()));
    }

    @Operation(summary = "Enable or disable a user (ADMIN)")
    @PutMapping("/{userId}/status")
    public UserResponse updateStatus(@PathVariable UUID userId, @RequestBody UpdateStatusRequest request,
            Authentication authentication) {
        UUID actor = AuthenticatedUser.from(authentication).requireUserId();
        return UserResponse.from(accounts.updateEnabled(actor, userId, request.enabled()));
    }
}
