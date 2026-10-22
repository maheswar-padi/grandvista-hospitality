package com.grandvista.payment.service;

import com.grandvista.payment.domain.Payment;
import com.grandvista.payment.domain.PaymentStatus;
import com.grandvista.payment.domain.Refund;
import com.grandvista.payment.provider.PaymentProvider;
import com.grandvista.payment.repository.PaymentRepository;
import com.grandvista.payment.repository.RefundRepository;
import com.grandvista.payment.web.dto.PaymentDtos.RefundRequest;
import com.grandvista.platform.error.ApiException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Refunds requested by the booking-service (cancellation policy or compensation). Idempotent per
 * idempotency key; a refund left PENDING by a provider outage is retried by repeating the same request.
 * There is deliberately no endpoint that lets a person mark a payment as refunded.
 */
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final PaymentProvider provider;
    private final PaymentAudit audit;
    private final TransactionTemplate tx;
    private final Clock clock;

    public RefundService(PaymentRepository payments, RefundRepository refunds, PaymentProvider provider,
            PaymentAudit audit, TransactionTemplate tx, Clock clock) {
        this.payments = payments;
        this.refunds = refunds;
        this.provider = provider;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    public Refund refund(UUID paymentId, RefundRequest request) {
        Refund refund = tx.execute(status -> begin(paymentId, request));
        if (refund.getStatus() != Refund.Status.PENDING) {
            return refund;
        }
        PaymentProvider.RefundResult result;
        try {
            Payment payment = payments.findById(paymentId).orElseThrow();
            result = provider.refund(new PaymentProvider.RefundRequest(request.idempotencyKey(),
                    payment.getProviderReference(), refund.getAmount()));
        } catch (PaymentProvider.ProviderUnavailableException ex) {
            log.warn("Refund {} left PENDING: provider unavailable ({})", refund.getId(), ex.getMessage());
            return refund;
        }
        return tx.execute(status -> complete(paymentId, refund.getId(), result));
    }

    private Refund begin(UUID paymentId, RefundRequest request) {
        var existing = refunds.findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            Refund r = existing.get();
            if (!r.getPaymentId().equals(paymentId) || r.getAmount().compareTo(request.amount()) != 0) {
                throw ApiException.conflict("REFUND_KEY_REUSED", "This idempotency key was used for a different refund.");
            }
            return r;
        }
        Payment payment = payments.findByIdForUpdate(paymentId).orElseThrow(PaymentService::notFound);
        if (payment.getStatus() != PaymentStatus.SUCCEEDED && payment.getStatus() != PaymentStatus.PARTIALLY_REFUNDED) {
            throw ApiException.conflict("PAYMENT_NOT_REFUNDABLE",
                    "Only captured payments can be refunded (status " + payment.getStatus() + ").");
        }
        if (request.amount().compareTo(payment.refundableAmount()) > 0) {
            throw ApiException.unprocessable("REFUND_EXCEEDS_CAPTURED",
                    "Refund exceeds the refundable amount of " + payment.refundableAmount() + ".");
        }
        Instant now = clock.instant();
        Refund refund = refunds.save(new Refund(paymentId, request.amount(), request.reason(), request.idempotencyKey(), now));
        PaymentStatus from = payment.transitionTo(PaymentStatus.REFUND_PENDING, now);
        audit.record(paymentId, "REFUND_REQUESTED", from, PaymentStatus.REFUND_PENDING,
                request.amount() + " " + payment.getCurrency() + " (" + request.reason() + ")");
        return refund;
    }

    private Refund complete(UUID paymentId, UUID refundId, PaymentProvider.RefundResult result) {
        Payment payment = payments.findByIdForUpdate(paymentId).orElseThrow();
        Refund refund = refunds.findById(refundId).orElseThrow();
        if (refund.getStatus() != Refund.Status.PENDING) {
            return refund;
        }
        Instant now = clock.instant();
        if (result.succeeded()) {
            refund.succeed(result.providerReference(), now);
            payment.addRefunded(refund.getAmount());
        } else {
            refund.fail(result.failureMessage(), now);
        }
        PaymentStatus target = payment.getRefundedAmount().compareTo(payment.getAmount()) == 0 ? PaymentStatus.REFUNDED
                : payment.getRefundedAmount().compareTo(BigDecimal.ZERO) > 0 ? PaymentStatus.PARTIALLY_REFUNDED
                : PaymentStatus.SUCCEEDED;
        if (payment.getStatus() == PaymentStatus.REFUND_PENDING) {
            PaymentStatus from = payment.transitionTo(target, now);
            audit.record(paymentId, result.succeeded() ? "REFUND_SUCCEEDED" : "REFUND_FAILED", from, target,
                    result.succeeded() ? result.providerReference() : result.failureMessage());
        }
        return refund;
    }
}
