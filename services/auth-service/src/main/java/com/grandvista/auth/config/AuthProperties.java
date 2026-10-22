package com.grandvista.auth.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gv.auth")
public record AuthProperties(
        Duration accessTokenTtl,
        Duration serviceTokenTtl,
        Duration refreshTokenTtl,
        Keys keys,
        Cookie cookie,
        Lockout lockout,
        BootstrapAdmin bootstrapAdmin,
        DemoUsers demoUsers,
        List<ServiceClient> serviceClients) {

    public AuthProperties {
        accessTokenTtl = accessTokenTtl == null ? Duration.ofMinutes(15) : accessTokenTtl;
        serviceTokenTtl = serviceTokenTtl == null ? Duration.ofMinutes(5) : serviceTokenTtl;
        refreshTokenTtl = refreshTokenTtl == null ? Duration.ofDays(7) : refreshTokenTtl;
        keys = keys == null ? new Keys(null, null, true) : keys;
        cookie = cookie == null ? new Cookie(null, true, null) : cookie;
        lockout = lockout == null ? new Lockout(5, Duration.ofMinutes(15)) : lockout;
        bootstrapAdmin = bootstrapAdmin == null ? new BootstrapAdmin(null, null, null) : bootstrapAdmin;
        demoUsers = demoUsers == null ? new DemoUsers(false, null, null, null, null) : demoUsers;
        serviceClients = serviceClients == null ? List.of() : serviceClients;
    }

    /**
     * RSA signing key locations (PEM). When absent and {@code allowEphemeral} is true an in-memory key is
     * generated at start-up (development only); production profiles set {@code allowEphemeral=false}.
     */
    public record Keys(String privateKeyLocation, String publicKeyLocation, boolean allowEphemeral) {
    }

    /** Refresh-token cookie. {@code secure} must stay true outside plain-HTTP local development. */
    public record Cookie(String name, boolean secure, String path) {
        public Cookie {
            name = name == null ? "gv_refresh" : name;
            path = path == null ? "/api/v1/auth" : path;
        }
    }

    public record Lockout(int maxFailedAttempts, Duration lockDuration) {
    }

    /** Creates the first administrator from environment variables when no administrator exists. */
    public record BootstrapAdmin(String email, String password, String fullName) {
        public boolean configured() {
            return email != null && !email.isBlank() && password != null && !password.isBlank();
        }

        @Override
        public String toString() {
            return "BootstrapAdmin[email=" + email + ", password=***]";
        }
    }

    /**
     * Optional demonstration accounts (one customer, one staff member) created at start-up when enabled and
     * not yet present. Passwords come only from the environment; the production profile disables this.
     */
    public record DemoUsers(boolean enabled, String customerEmail, String customerPassword, String staffEmail,
                            String staffPassword) {
        @Override
        public String toString() {
            return "DemoUsers[enabled=" + enabled + ", customerEmail=" + customerEmail + ", staffEmail=" + staffEmail
                    + ", passwords=***]";
        }
    }

    public record ServiceClient(String clientId, String clientSecret) {
        @Override
        public String toString() {
            return "ServiceClient[clientId=" + clientId + ", clientSecret=***]";
        }
    }
}
