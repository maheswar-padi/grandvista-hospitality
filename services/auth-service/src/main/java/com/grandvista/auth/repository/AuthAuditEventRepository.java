package com.grandvista.auth.repository;

import com.grandvista.auth.domain.AuthAuditEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthAuditEventRepository extends JpaRepository<AuthAuditEvent, Long> {

    List<AuthAuditEvent> findByUserIdOrderByOccurredAtDesc(UUID userId);
}
