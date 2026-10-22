package com.grandvista.payment.provider.simulated;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SimulatedChargeRepository extends JpaRepository<SimulatedCharge, UUID> {

    Optional<SimulatedCharge> findByIdempotencyKey(String idempotencyKey);

    Optional<SimulatedCharge> findByReference(String reference);

    @Query(value = """
            SELECT * FROM simulated_provider.charges
            WHERE webhook_sent_at IS NULL AND webhook_due_at IS NOT NULL AND webhook_due_at <= :now
              AND webhook_attempts < 10
            ORDER BY webhook_due_at LIMIT 20
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<SimulatedCharge> claimDueWebhooks(Instant now);

    @Query(value = "SELECT coalesce(sum(amount), 0) FROM simulated_provider.refunds WHERE charge_ref = :reference",
            nativeQuery = true)
    BigDecimal refundedAmount(String reference);
}
