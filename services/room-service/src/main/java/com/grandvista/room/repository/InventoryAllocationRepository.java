package com.grandvista.room.repository;

import com.grandvista.room.domain.InventoryAllocation;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface InventoryAllocationRepository extends JpaRepository<InventoryAllocation, UUID>,
        JpaSpecificationExecutor<InventoryAllocation> {

    Optional<InventoryAllocation> findByBookingId(UUID bookingId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from InventoryAllocation a where a.bookingId = :bookingId")
    Optional<InventoryAllocation> findByBookingIdForUpdate(UUID bookingId);

    /** Allocations that block the room within [from, to) — expired-but-unswept holds do not count. */
    @Query("""
            select a from InventoryAllocation a
            where a.roomId = :roomId
              and a.checkIn < :to and a.checkOut > :from
              and (a.status = com.grandvista.room.domain.AllocationStatus.CONFIRMED
                   or (a.status = com.grandvista.room.domain.AllocationStatus.HELD and a.holdExpiresAt > :now))
            order by a.checkIn
            """)
    List<InventoryAllocation> findBlocking(UUID roomId, LocalDate from, LocalDate to, Instant now);

    /** Stale holds on this room for these dates; they are expired before a new hold is inserted. */
    @Query("""
            select a from InventoryAllocation a
            where a.roomId = :roomId and a.status = com.grandvista.room.domain.AllocationStatus.HELD
              and a.holdExpiresAt <= :now and a.checkIn < :to and a.checkOut > :from
            """)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<InventoryAllocation> findStaleHoldsForUpdate(UUID roomId, LocalDate from, LocalDate to, Instant now);

    @Query(value = """
            SELECT * FROM inventory_allocations
            WHERE status = 'HELD' AND hold_expires_at <= :now
            ORDER BY hold_expires_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<InventoryAllocation> findDueHoldsSkipLocked(Instant now, int limit);

    /** HELD or CONFIRMED allocations of these rooms whose stay has not ended (reconciliation input). */
    @Query("""
            select a from InventoryAllocation a
            where a.roomId in :roomIds and a.checkOut > :today
              and a.status in (com.grandvista.room.domain.AllocationStatus.HELD,
                               com.grandvista.room.domain.AllocationStatus.CONFIRMED)
            """)
    List<InventoryAllocation> findActiveForRooms(Collection<UUID> roomIds, LocalDate today);

    List<InventoryAllocation> findAllByBookingIdIn(Collection<UUID> bookingIds);

    @Query("""
            select count(a) from InventoryAllocation a
            where a.roomId = :roomId and a.status = com.grandvista.room.domain.AllocationStatus.CONFIRMED
              and a.checkOut > :today
            """)
    long countFutureConfirmed(UUID roomId, LocalDate today);

}
