package com.grandvista.auth.service;

import com.grandvista.auth.config.AuthProperties;
import com.grandvista.auth.domain.RefreshToken;
import com.grandvista.auth.repository.RefreshTokenRepository;
import com.grandvista.platform.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opaque, rotating refresh tokens. Only SHA-256 hashes are stored. Presenting a token that was already
 * rotated is treated as theft: the entire token family is revoked and the user must sign in again.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Rotation(UUID userId, String newToken, Instant expiresAt) {
    }

    private final RefreshTokenRepository repository;
    private final AuthProperties properties;
    private final AuditService audit;
    private final Clock clock;

    public RefreshTokenService(RefreshTokenRepository repository, AuthProperties properties, AuditService audit,
            Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public String issue(UUID userId) {
        return create(userId, UUID.randomUUID()).raw;
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Rotation rotate(String rawToken) {
        Instant now = clock.instant();
        RefreshToken current = repository.findByHashForUpdate(hash(rawToken))
                .orElseThrow(RefreshTokenService::invalid);
        if (current.getRevokedAt() != null) {
            int revoked = repository.revokeFamily(current.getFamilyId(), now);
            audit.record(current.getUserId(), com.grandvista.auth.domain.AuthAuditEvent.Type.REFRESH_REUSE_DETECTED,
                    "revoked " + revoked + " active token(s) in family");
            log.warn("Refresh token reuse detected for user {}; token family revoked", current.getUserId());
            throw invalid();
        }
        if (!current.isActive(now)) {
            throw invalid();
        }
        Created next = create(current.getUserId(), current.getFamilyId());
        current.revoke(now, next.entity.getId());
        return new Rotation(current.getUserId(), next.raw, next.entity.getExpiresAt());
    }

    /** Logout: revokes the presented token's family. Unknown tokens are ignored (idempotent logout). */
    @Transactional
    public Optional<UUID> revoke(String rawToken) {
        return repository.findByHashForUpdate(hash(rawToken)).map(token -> {
            repository.revokeFamily(token.getFamilyId(), clock.instant());
            return token.getUserId();
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeAllForUser(UUID userId) {
        repository.revokeAllForUser(userId, clock.instant());
    }

    @Scheduled(cron = "${gv.auth.refresh-token-cleanup-cron:0 17 3 * * *}")
    @Transactional
    public void purgeExpired() {
        int deleted = repository.deleteExpiredBefore(clock.instant().minus(Duration.ofDays(1)));
        if (deleted > 0) {
            log.info("Purged {} expired refresh tokens", deleted);
        }
    }

    private record Created(String raw, RefreshToken entity) {
    }

    private Created create(UUID userId, UUID familyId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();
        RefreshToken entity = repository.save(
                new RefreshToken(userId, familyId, hash(raw), now.plus(properties.refreshTokenTtl()), now));
        return new Created(raw, entity);
    }

    static String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN",
                "Your session has expired. Please sign in again.");
    }
}
