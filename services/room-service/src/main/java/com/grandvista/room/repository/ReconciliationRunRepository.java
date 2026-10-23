package com.grandvista.room.repository;

import com.grandvista.room.domain.ReconciliationRun;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, UUID> {

    Optional<ReconciliationRun> findFirstByPropertyIdOrderByStartedAtDesc(UUID propertyId);

    Optional<ReconciliationRun> findFirstByPropertyIdAndStatusOrderByStartedAtDesc(UUID propertyId,
            ReconciliationRun.Status status);

    List<ReconciliationRun> findByPropertyIdOrderByStartedAtDesc(UUID propertyId, Pageable page);

    /** Transaction-scoped advisory lock so only one instance reconciles a property at a time. */
    @Query(value = "SELECT pg_try_advisory_xact_lock(hashtext('inventory-reconciliation'), hashtext(CAST(:propertyId AS text)))",
            nativeQuery = true)
    boolean tryLock(UUID propertyId);
}
