package com.grandvista.booking.repository;

import com.grandvista.booking.domain.OutboxEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /** Claims the next due event; SKIP LOCKED lets several booking-service replicas relay without double delivery. */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE status = 'PENDING' AND next_attempt_at <= :now
            ORDER BY created_at
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> claimNext(Instant now);

    long countByStatus(OutboxEvent.Status status);

    List<OutboxEvent> findByAggregateIdOrderByCreatedAtAsc(UUID aggregateId);
}
