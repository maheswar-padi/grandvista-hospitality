package com.grandvista.auth.service;

import com.grandvista.auth.config.AuthProperties;
import com.grandvista.auth.domain.AuthAuditEvent.Type;
import com.grandvista.auth.domain.Role;
import com.grandvista.auth.domain.UserAccount;
import com.grandvista.auth.repository.UserAccountRepository;
import com.grandvista.auth.web.dto.AuthDtos.RegisterRequest;
import com.grandvista.platform.error.ApiException;
import com.grandvista.platform.persistence.Specs;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    /** Same message for unknown e-mail and wrong password, so accounts cannot be enumerated. */
    static final String BAD_CREDENTIALS = "Invalid e-mail address or password.";

    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;
    private final AuditService audit;
    private final RefreshTokenService refreshTokens;
    private final Clock clock;
    /** Hash verified when the e-mail is unknown, so response time does not reveal whether it exists. */
    private final String dummyHash;

    public AccountService(UserAccountRepository users, PasswordEncoder passwordEncoder, AuthProperties properties,
            AuditService audit, RefreshTokenService refreshTokens, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.audit = audit;
        this.refreshTokens = refreshTokens;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    @Transactional
    public UserAccount register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (users.existsByEmail(email)) {
            throw ApiException.conflict("EMAIL_ALREADY_REGISTERED", "An account with this e-mail address already exists.");
        }
        UserAccount account = new UserAccount(email, passwordEncoder.encode(request.password()),
                request.fullName().trim(), blankToNull(request.phone()), EnumSet.of(Role.CUSTOMER), clock.instant());
        try {
            users.saveAndFlush(account);
        } catch (DataIntegrityViolationException race) {
            throw ApiException.conflict("EMAIL_ALREADY_REGISTERED", "An account with this e-mail address already exists.");
        }
        audit.record(account.getId(), Type.REGISTERED, null);
        return account;
    }

    /**
     * Verifies credentials with per-account lockout. Failed attempts are committed even though the call
     * ends in an exception, which is why {@code noRollbackFor} is set.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public UserAccount authenticate(String rawEmail, String password) {
        Instant now = clock.instant();
        UserAccount account = users.findByEmail(normalizeEmail(rawEmail)).orElse(null);
        if (account == null) {
            passwordEncoder.matches(password, dummyHash);
            throw badCredentials();
        }
        if (account.isLocked(now)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "ACCOUNT_TEMPORARILY_LOCKED",
                    "Too many failed sign-in attempts. Try again later.");
        }
        if (!passwordEncoder.matches(password, account.getPasswordHash())) {
            AuthProperties.Lockout lockout = properties.lockout();
            account.registerFailedLogin(lockout.maxFailedAttempts(), lockout.lockDuration(), now);
            audit.record(account.getId(), Type.LOGIN_FAILED, null);
            if (account.isLocked(now)) {
                audit.record(account.getId(), Type.ACCOUNT_LOCKED, "locked until " + account.getLockedUntil());
            }
            throw badCredentials();
        }
        if (!account.isEnabled()) {
            throw ApiException.forbidden("ACCOUNT_DISABLED", "This account has been disabled.");
        }
        account.registerSuccessfulLogin(now);
        audit.record(account.getId(), Type.LOGIN_SUCCEEDED, null);
        return account;
    }

    /** Loads the account behind a refresh token, refusing disabled accounts. */
    @Transactional(readOnly = true)
    public UserAccount requireActive(UUID userId) {
        UserAccount account = users.findById(userId).orElseThrow(AccountService::sessionInvalid);
        if (!account.isEnabled()) {
            throw sessionInvalid();
        }
        return account;
    }

    @Transactional(readOnly = true)
    public UserAccount get(UUID userId) {
        return users.findById(userId).orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "User not found."));
    }

    @Transactional(readOnly = true)
    public Page<UserAccount> search(String query, Pageable pageable) {
        Specification<UserAccount> filter = Specs.allOf(Specs.containsIgnoreCase(query, "email", "fullName"));
        return users.findAll(filter, pageable);
    }

    @Transactional
    public UserAccount updateRoles(UUID actorId, UUID userId, Set<Role> roles) {
        UserAccount account = get(userId);
        if (actorId.equals(userId) && !roles.contains(Role.ADMIN)) {
            throw ApiException.unprocessable("CANNOT_REMOVE_OWN_ADMIN", "You cannot remove your own administrator role.");
        }
        account.replaceRoles(roles, clock.instant());
        refreshTokens.revokeAllForUser(userId); // force re-login so new roles take effect
        audit.record(userId, Type.ROLES_CHANGED, "roles=" + roles + " by " + actorId);
        return account;
    }

    @Transactional
    public UserAccount updateEnabled(UUID actorId, UUID userId, boolean enabled) {
        if (actorId.equals(userId) && !enabled) {
            throw ApiException.unprocessable("CANNOT_DISABLE_SELF", "You cannot disable your own account.");
        }
        UserAccount account = get(userId);
        account.setEnabled(enabled, clock.instant());
        if (!enabled) {
            refreshTokens.revokeAllForUser(userId);
        }
        audit.record(userId, enabled ? Type.ACCOUNT_ENABLED : Type.ACCOUNT_DISABLED, "by " + actorId);
        return account;
    }

    private static ApiException badCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", BAD_CREDENTIALS);
    }

    private static ApiException sessionInvalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN",
                "Your session has expired. Please sign in again.");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
