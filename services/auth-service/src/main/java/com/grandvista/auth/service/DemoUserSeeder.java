package com.grandvista.auth.service;

import com.grandvista.auth.config.AuthProperties;
import com.grandvista.auth.domain.AuthAuditEvent.Type;
import com.grandvista.auth.domain.Role;
import com.grandvista.auth.domain.UserAccount;
import com.grandvista.auth.repository.UserAccountRepository;
import java.time.Clock;
import java.util.EnumSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the demonstration customer and staff accounts when {@code gv.auth.demo-users.enabled} is true.
 * Existing accounts are never modified, so a changed password in {@code .env} is not applied retroactively.
 * Passwords are never logged.
 */
@Component
@Order(20)
public class DemoUserSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoUserSeeder.class);
    private static final int MIN_PASSWORD_LENGTH = 10;

    private final AuthProperties properties;
    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final Clock clock;

    public DemoUserSeeder(AuthProperties properties, UserAccountRepository users, PasswordEncoder passwordEncoder,
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
        AuthProperties.DemoUsers demo = properties.demoUsers();
        if (!demo.enabled()) {
            return;
        }
        create(demo.customerEmail(), demo.customerPassword(), "Demo Guest", EnumSet.of(Role.CUSTOMER));
        create(demo.staffEmail(), demo.staffPassword(), "Demo Front Desk", EnumSet.of(Role.STAFF));
    }

    private void create(String rawEmail, String password, String name, Set<Role> roles) {
        if (rawEmail == null || rawEmail.isBlank() || password == null || password.length() < MIN_PASSWORD_LENGTH) {
            log.warn("Demo account {} skipped: e-mail missing or password shorter than {} characters", roles,
                    MIN_PASSWORD_LENGTH);
            return;
        }
        String email = AccountService.normalizeEmail(rawEmail);
        if (users.findByEmail(email).isPresent()) {
            return;
        }
        UserAccount account = users.save(new UserAccount(email, passwordEncoder.encode(password), name, null,
                EnumSet.copyOf(roles), clock.instant()));
        audit.record(account.getId(), Type.REGISTERED, null);
        log.info("Demo account {} created for {}", roles, AdminBootstrap.mask(email));
    }
}
