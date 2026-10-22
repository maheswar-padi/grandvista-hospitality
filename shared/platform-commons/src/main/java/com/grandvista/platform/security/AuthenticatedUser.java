package com.grandvista.platform.security;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The caller identity as established by a verified JWT. Never built from request bodies or headers
 * supplied by the browser.
 */
public record AuthenticatedUser(UUID id, String subject, String email, String fullName, Set<String> roles) {

    public static AuthenticatedUser from(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            throw new InsufficientAuthenticationException("A bearer token is required");
        }
        var jwt = token.getToken();
        Collection<String> roles = jwt.getClaimAsStringList("roles");
        UUID id = null;
        try {
            id = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException ignored) {
            // service principals use non-UUID subjects such as "service:booking-service"
        }
        return new AuthenticatedUser(id, jwt.getSubject(), jwt.getClaimAsString("email"),
                jwt.getClaimAsString("name"), roles == null ? Set.of() : roles.stream().collect(Collectors.toUnmodifiableSet()));
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean isAdmin() {
        return hasRole(Roles.ADMIN);
    }

    /** Hotel personnel: staff or administrators. */
    public boolean isPersonnel() {
        return hasRole(Roles.ADMIN) || hasRole(Roles.STAFF);
    }

    public UUID requireUserId() {
        if (id == null) {
            throw new InsufficientAuthenticationException("A user token is required");
        }
        return id;
    }
}
