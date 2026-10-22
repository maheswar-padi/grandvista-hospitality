package com.grandvista.auth.service;

import com.grandvista.auth.config.AuthProperties;
import com.grandvista.auth.domain.AuthAuditEvent.Type;
import com.grandvista.auth.domain.Role;
import com.grandvista.auth.domain.UserAccount;
import com.grandvista.auth.repository.UserAccountRepository;
import java.time.Clock;
import java.util.EnumSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first administrator from {@code GV_BOOTSTRAP_ADMIN_EMAIL} / {@code GV_BOOTSTRAP_ADMIN_PASSWORD}
 * only if no administrator exists yet. There is no default password and no unauthenticated API that can
 * create an administrator. The password is never logged.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
    private static final int MIN_PASSWORD_LENGTH = 12;

    private final AuthProperties properties;
    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final Clock clock;

    public AdminBootstrap(AuthProperties properties, UserAccountRepository users, PasswordEncoder passwordEncoder,
            AuditService audit, Clock clock) {
        this.properties = properties;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        AuthProperties.BootstrapAdmin admin = properties.bootstrapAdmin();
        if (users.existsByRole(Role.ADMIN)) {
            return;
        }
        if (!admin.configured()) {
            log.warn("No administrator exists and GV_BOOTSTRAP_ADMIN_EMAIL/GV_BOOTSTRAP_ADMIN_PASSWORD are not set. "
                    + "Administrative features are unavailable until an administrator is bootstrapped.");
            return;
        }
        if (admin.password().length() < MIN_PASSWORD_LENGTH) {
            log.error("Bootstrap administrator password is shorter than {} characters; administrator NOT created",
                    MIN_PASSWORD_LENGTH);
            return;
        }
        String email = AccountService.normalizeEmail(admin.email());
        UserAccount account = users.findByEmail(email).orElse(null);
        if (account == null) {
            String name = admin.fullName() == null || admin.fullName().isBlank() ? "Platform Administrator" : admin.fullName();
            account = users.save(new UserAccount(email, passwordEncoder.encode(admin.password()), name, null,
                    EnumSet.of(Role.ADMIN, Role.STAFF), clock.instant()));
        } else {
            account.replaceRoles(EnumSet.of(Role.ADMIN, Role.STAFF), clock.instant());
        }
        audit.record(account.getId(), Type.ADMIN_BOOTSTRAPPED, null);
        log.info("Bootstrap administrator account is ready for {}", mask(email));
    }

    static String mask(String email) {
        int at = email.indexOf('@');
        return at <= 1 ? "***" + email.substring(Math.max(at, 0)) : email.charAt(0) + "***" + email.substring(at);
    }
}
