package com.grandvista.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UserAccountTest {

    private final Instant now = Instant.parse("2026-01-10T10:00:00Z");

    @Test
    void locksAfterThresholdAndUnlocksAfterDuration() {
        UserAccount user = new UserAccount("a@b.test", "hash", "A", null, EnumSet.of(Role.CUSTOMER), now);
        for (int i = 0; i < 4; i++) {
            user.registerFailedLogin(5, Duration.ofMinutes(15), now);
        }
        assertThat(user.isLocked(now)).isFalse();
        user.registerFailedLogin(5, Duration.ofMinutes(15), now);
        assertThat(user.isLocked(now)).isTrue();
        assertThat(user.isLocked(now.plus(Duration.ofMinutes(16)))).isFalse();
    }

    @Test
    void successfulLoginResetsFailureCounter() {
        UserAccount user = new UserAccount("a@b.test", "hash", "A", null, EnumSet.of(Role.CUSTOMER), now);
        user.registerFailedLogin(5, Duration.ofMinutes(15), now);
        user.registerSuccessfulLogin(now);
        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.getLastLoginAt()).isEqualTo(now);
    }

    @Test
    void emptyRoleSetFallsBackToCustomer() {
        UserAccount user = new UserAccount("a@b.test", "hash", "A", null, EnumSet.of(Role.ADMIN), now);
        user.replaceRoles(Set.of(), now);
        assertThat(user.getRoles()).containsExactly(Role.CUSTOMER);
    }
}
