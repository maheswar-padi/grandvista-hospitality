package com.grandvista.payment.repository;

import com.grandvista.payment.domain.Payment;
import com.grandvista.payment.domain.PaymentStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PaymentRepository extends JpaRepository<Payment, UUID>, JpaSpecificationExecutor<Payment> {

    Optional<Payment> findByBookingId(UUID bookingId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findByIdForUpdate(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.provider = :provider and p.providerReference = :reference")
    Optional<Payment> findByProviderReferenceForUpdate(String provider, String reference);

    Page<Payment> findByCustomerId(UUID customerId, Pageable pageable);


    /** Payments stuck in PENDING (provider outcome unknown) that the reconciler should check. */
    @Query("select p.id from Payment p where p.status = com.grandvista.payment.domain.PaymentStatus.PENDING and p.updatedAt < :before")
    List<UUID> findPendingIdsUpdatedBefore(Instant before);

    @Query("select p.status as status, count(p) as count, coalesce(sum(p.amount), 0) as amount, "
            + "coalesce(sum(p.refundedAmount), 0) as refunded from Payment p group by p.status")
    List<StatusTotals> totalsByStatus();

    interface StatusTotals {
        PaymentStatus getStatus();

        long getCount();

        java.math.BigDecimal getAmount();

        java.math.BigDecimal getRefunded();
    }
}
