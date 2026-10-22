package com.grandvista.auth.web.dto;

import com.grandvista.auth.domain.Role;
import com.grandvista.auth.domain.UserAccount;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public final class AuthDtos {

    private AuthDtos() {
    }

    /** Registration never accepts a role: every self-registered account is a CUSTOMER. */
    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank
            @Size(min = 10, max = 128, message = "must be between 10 and 128 characters")
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "must contain at least one letter and one digit")
            String password,
            @NotBlank @Size(min = 2, max = 120) String fullName,
            @Pattern(regexp = "^\\+?[0-9 ()-]{6,32}$", message = "must be a valid phone number") String phone) {

        @Override
        public String toString() {
            return "RegisterRequest[email=" + email + ", password=***]";
        }
    }

    public record LoginRequest(@NotBlank @Email @Size(max = 254) String email,
                               @NotBlank @Size(max = 128) String password) {
        @Override
        public String toString() {
            return "LoginRequest[email=" + email + ", password=***]";
        }
    }

    public record UserResponse(UUID id, String email, String fullName, String phone, Set<Role> roles,
                               boolean enabled, Instant createdAt, Instant lastLoginAt) {
        public static UserResponse from(UserAccount user) {
            return new UserResponse(user.getId(), user.getEmail(), user.getFullName(), user.getPhone(),
                    user.getRoles(), user.isEnabled(), user.getCreatedAt(), user.getLastLoginAt());
        }
    }

    @Schema(description = "Access token for the Authorization header. The refresh token is set as an HttpOnly cookie.")
    public record AuthResponse(String accessToken, String tokenType, long expiresIn, UserResponse user) {
    }

    public record UpdateRolesRequest(@NotEmpty Set<Role> roles) {
    }

    public record UpdateStatusRequest(boolean enabled) {
    }

    public record ServiceTokenResponse(
            @com.fasterxml.jackson.annotation.JsonProperty("access_token") String accessToken,
            @com.fasterxml.jackson.annotation.JsonProperty("token_type") String tokenType,
            @com.fasterxml.jackson.annotation.JsonProperty("expires_in") long expiresIn) {
    }
}
