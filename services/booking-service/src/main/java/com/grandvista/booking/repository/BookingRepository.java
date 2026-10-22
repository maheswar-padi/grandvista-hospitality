package com.grandvista.booking.repository;

import com.grandvista.booking.domain.Booking;
import com.grandvista.booking.domain.BookingStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface BookingRepository extends JpaRepository<Booking, UUID>, JpaSpecificationExecutor<Booking> {

    Optional<Booking> findByCustomerIdAndIdempotencyKey(UUID customerId, String idempotencyKey);

    Optional<Booking> findByReference(String reference);

    boolean existsByReference(String reference);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.id = :id")
    Optional<Booking> findByIdForUpdate(UUID id);


    @Query("select b.id from Booking b where b.status = :status and b.updatedAt < :before order by b.updatedAt")
    List<UUID> findIdsByStatusUpdatedBefore(BookingStatus status, Instant before, Pageable pageable);

    @Query("select b.id from Booking b where b.status = com.grandvista.booking.domain.BookingStatus.PENDING_PAYMENT "
            + "and b.holdExpiresAt < :before order by b.holdExpiresAt")
    List<UUID> findExpiredPendingPaymentIds(Instant before, Pageable pageable);

    @Query("select b.status as status, count(b) as count, coalesce(sum(b.totalAmount), 0) as amount from Booking b group by b.status")
    List<StatusCount> countByStatus();

    @Query(value = """
            SELECT d::date AS day, count(b.id) AS created,
                   count(b.id) FILTER (WHERE b.status IN ('CONFIRMED', 'CANCELLATION_PENDING', 'CANCELLED')
                                       AND b.confirmed_at IS NOT NULL) AS confirmed
            FROM generate_series(CAST(:from AS date), CAST(:to AS date), interval '1 day') d
            LEFT JOIN bookings b ON (b.created_at AT TIME ZONE :zone)::date = d::date
            GROUP BY d ORDER BY d
            """, nativeQuery = true)
    List<DailyCount> dailyCounts(LocalDate from, LocalDate to, String zone);

    @Query("select count(b) from Booking b where b.status = :status and b.checkIn = :date")
    long countByStatusOnCheckInDate(BookingStatus status, LocalDate date);

    interface StatusCount {
        BookingStatus getStatus();

        long getCount();

        java.math.BigDecimal getAmount();
    }

    interface DailyCount {
        java.sql.Date getDay();

        long getCreated();

        long getConfirmed();
    }
}
