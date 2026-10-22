package com.grandvista.room.repository;

import com.grandvista.room.domain.PricingPolicy;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PricingPolicyRepository extends JpaRepository<PricingPolicy, UUID> {

    @Query("select p from PricingPolicy p where p.status = com.grandvista.room.domain.PricingPolicy.Status.ACTIVE")
    Optional<PricingPolicy> findActive();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PricingPolicy p where p.status = com.grandvista.room.domain.PricingPolicy.Status.ACTIVE")
    Optional<PricingPolicy> findActiveForUpdate();

    List<PricingPolicy> findAllByOrderByVersionDesc();

    @Query("select coalesce(max(p.version), 0) from PricingPolicy p")
    int maxVersion();
}
