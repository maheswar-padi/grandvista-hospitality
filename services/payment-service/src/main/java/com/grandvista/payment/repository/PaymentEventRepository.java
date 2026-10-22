package com.grandvista.payment.repository;

import com.grandvista.payment.domain.PaymentEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentEventRepository extends JpaRepository<PaymentEvent, Long> {

    List<PaymentEvent> findByPaymentIdOrderByOccurredAtAscIdAsc(UUID paymentId);
}
