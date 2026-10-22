package com.grandvista.payment.repository;

import com.grandvista.payment.domain.PaymentAttempt;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {

    Optional<PaymentAttempt> findByPaymentIdAndIdempotencyKey(UUID paymentId, String idempotencyKey);

    Optional<PaymentAttempt> findFirstByPaymentIdOrderByCreatedAtDesc(UUID paymentId);
}
