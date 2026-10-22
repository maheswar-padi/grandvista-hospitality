package com.grandvista.room;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
public class TestSupport {

    /** A clock tests can move forward to exercise hold expiry deterministically. */
    public static class MutableClock extends Clock {
        private volatile Instant now = Instant.now();

        public void advance(Duration d) {
            now = now.plus(d);
        }

        public void reset() {
            now = Instant.now();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:16-alpine");
    }

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock();
    }

    public static JwtRequestPostProcessor serviceToken() {
        return jwt().jwt(j -> j.subject("service:booking-service").claim("roles", List.of("SERVICE")))
                .authorities(new SimpleGrantedAuthority("ROLE_SERVICE"));
    }

    public static JwtRequestPostProcessor user(String role) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("roles", List.of(role)))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
