package com.grandvista.payment.service;

import com.grandvista.payment.domain.Payment;
import com.grandvista.payment.domain.PaymentAttempt;
import com.grandvista.payment.domain.PaymentStatus;
import com.grandvista.payment.provider.PaymentProvider;
import com.grandvista.payment.provider.PaymentProvider.ChargeResult;
import com.grandvista.payment.repository.PaymentAttemptRepository;
import com.grandvista.payment.repository.PaymentRepository;
import com.grandvista.payment.web.dto.PaymentDtos.CreatePaymentRequest;
import com.grandvista.platform.error.ApiException;
import com.grandvista.platform.security.AuthenticatedUser;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Payment lifecycle. The provider call happens <em>between</em> two short transactions so no database
 * lock is held while waiting on the network:
 * <ol>
 *   <li>tx 1: verify ownership/state/expiry, CREATED → PENDING, record the attempt</li>
 *   <li>provider charge (idempotency key = payment id, so a retry can never double-charge)</li>
 *   <li>tx 2: apply the outcome; SUCCEEDED/FAILED also write an outbox event for the booking-service</li>
 * </ol>
 * If the provider times out the payment stays PENDING and {@link PaymentReconciliationJob} later asks the
 * provider for the authoritative outcome.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository payments;
    private final PaymentAttemptRepository attempts;
    private final PaymentProvider provider;
    private final PaymentAudit audit;
    private final OutboxService outbox;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PaymentService(PaymentRepository payments, PaymentAttemptRepository attempts, PaymentProvider provider,
            PaymentAudit audit, OutboxService outbox, TransactionTemplate tx, Clock clock) {
        this.payments = payments;
        this.attempts = attempts;
        this.provider = provider;
        this.audit = audit;
        this.outbox = outbox;
        this.tx = tx;
        this.clock = clock;
    }

    /** Idempotent per booking: a retried request returns the existing payment. */
    @Transactional
    public Payment create(CreatePaymentRequest request) {
        var existing = payments.findByBookingId(request.bookingId());
        if (existing.isPresent()) {
            return requireSameTerms(existing.get(), request);
        }
        Payment payment = new Payment(request.bookingId(), request.bookingReference(), request.customerId(),
                request.amount(), request.currency(), provider.name(), request.expiresAt(), clock.instant());
        try {
            payments.saveAndFlush(payment);
        } catch (DataIntegrityViolationException race) {
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "PAYMENT_CREATION_IN_PROGRESS",
                    "A payment for this booking is being created concurrently. Retry the request.");
        }
        audit.record(payment.getId(), "CREATED", null, PaymentStatus.CREATED,
                request.amount() + " " + request.currency() + " for booking " + request.bookingReference());
        return payment;
    }

    @Transactional(readOnly = true)
    public Payment getForCaller(UUID paymentId, AuthenticatedUser caller) {
        Payment payment = payments.findById(paymentId).orElseThrow(PaymentService::notFound);
        requireOwnerOrPersonnel(payment, caller);
        return payment;
    }

    @Transactional(readOnly = true)
    public Payment getByBookingForCaller(UUID bookingId, AuthenticatedUser caller) {
        Payment payment = payments.findByBookingId(bookingId).orElseThrow(PaymentService::notFound);
        requireOwnerOrPersonnel(payment, caller);
        return payment;
    }

    @Transactional(readOnly = true)
    public Payment getByBooking(UUID bookingId) {
        return payments.findByBookingId(bookingId).orElseThrow(PaymentService::notFound);
    }

    @Transactional(readOnly = true)
    public Page<Payment> history(UUID customerId, Pageable pageable) {
        return payments.findByCustomerId(customerId, pageable);
    }

    /**
     * Starts the provider checkout for the owner: creates the provider order once (Razorpay) and returns what the
     * browser needs. Repeated calls return the same order. The network call happens outside any transaction.
     */
    public PaymentProvider.CheckoutSession checkout(UUID paymentId, AuthenticatedUser caller) {
        UUID customerId = caller.requireUserId();
        Payment payment = tx.execute(status -> {
            Payment p = payments.findById(paymentId).orElseThrow(PaymentService::notFound);
            if (!p.getCustomerId().equals(customerId)) {
                throw notFound();
            }
            requirePayable(p, clock.instant());
            return p;
        });
        PaymentProvider.CheckoutSession session;
        try {
            session = provider.checkout(new PaymentProvider.CheckoutRequest(paymentId, payment.getAmount(),
                    payment.getCurrency(), payment.getBookingReference(), payment.getProviderOrderId(), payment.getExpiresAt()));
        } catch (PaymentProvider.ProviderUnavailableException ex) {
            log.warn("Checkout for payment {} failed: {}", paymentId, ex.getMessage());
            throw new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_PROVIDER_UNAVAILABLE",
                    "The payment provider is not reachable. Please try again in a moment.");
        }
        if (session.orderId() == null || session.orderId().equals(payment.getProviderOrderId())) {
            return session;
        }
        String orderId = tx.execute(status -> {
            Payment locked = payments.findByIdForUpdate(paymentId).orElseThrow();
            if (locked.attachProviderOrder(session.orderId())) {
                audit.record(paymentId, "CHECKOUT_STARTED", locked.getStatus(), locked.getStatus(),
                        provider.name() + " order " + session.orderId());
            }
            return locked.getProviderOrderId(); // a concurrent request may have attached its order first
        });
        return new PaymentProvider.CheckoutSession(session.provider(), session.publicKey(), orderId, session.amount(),
                session.currency(), session.amountMinorUnits());
    }

    /** Customer confirmation with a provider test token; idempotent per (payment, Idempotency-Key). */
    public Payment confirm(UUID paymentId, String idempotencyKey, String paymentMethodToken, AuthenticatedUser caller) {
        return confirm(paymentId, idempotencyKey, paymentMethodToken, null, caller);
    }

    /**
     * Customer confirmation with either a provider token (simulated) or a provider checkout result (Razorpay);
     * idempotent per (payment, Idempotency-Key).
     */
    public Payment confirm(UUID paymentId, String idempotencyKey, String paymentMethodToken,
            PaymentProvider.CheckoutResult checkout, AuthenticatedUser caller) {
        UUID customerId = caller.requireUserId();
        if (provider.usesCheckout() && checkout == null) {
            throw ApiException.badRequest("CHECKOUT_RESULT_REQUIRED",
                    "This payment must be completed in the " + provider.name() + " checkout.");
        }
        if (!provider.usesCheckout() && (checkout != null || paymentMethodToken == null)) {
            throw ApiException.badRequest("PAYMENT_METHOD_TOKEN_REQUIRED", "A payment-method token is required.");
        }
        String method = checkout != null ? provider.name().toLowerCase() + "_checkout" : paymentMethodToken;
        Started begun = tx.execute(status -> begin(paymentId, idempotencyKey, method, customerId, checkout != null));
        Payment started = begun.payment();
        if (!begun.newAttempt()) {
            return started; // replay of an earlier request with the same key: report the current state
        }

        ChargeResult result;
        try {
            result = provider.charge(new PaymentProvider.ChargeRequest(paymentId.toString(), started.getAmount(),
                    started.getCurrency(), paymentMethodToken, "Booking " + started.getBookingReference(),
                    started.getProviderOrderId(), checkout));
        } catch (PaymentProvider.ProviderUnavailableException ex) {
            log.warn("Provider outcome unknown for payment {}: {}. Leaving PENDING for reconciliation.", paymentId,
                    ex.getMessage());
            return tx.execute(status -> {
                markAttempt(paymentId, idempotencyKey, PaymentAttempt.Status.UNKNOWN, null, null);
                Payment p = payments.findByIdForUpdate(paymentId).orElseThrow();
                audit.record(p.getId(), "PROVIDER_TIMEOUT", p.getStatus(), p.getStatus(), "outcome unknown; will reconcile");
                return p;
            });
        }
        return tx.execute(status -> {
            markAttempt(paymentId, idempotencyKey, switch (result.outcome()) {
                case SUCCEEDED -> PaymentAttempt.Status.SUCCEEDED;
                case FAILED -> PaymentAttempt.Status.FAILED;
                case PENDING -> PaymentAttempt.Status.PENDING_PROVIDER;
            }, result.providerReference(), result.failureCode());
            return applyOutcome(paymentId, result, "PROVIDER_RESPONSE");
        });
    }

    /**
     * The provider reports money captured for a payment the customer never confirmed with us (e.g. the browser
     * closed after paying): move CREATED to PENDING so the outcome can be applied. No-op in any other status.
     */
    @Transactional
    public Payment markPendingFromProvider(UUID paymentId, String source) {
        Payment payment = payments.findByIdForUpdate(paymentId).orElseThrow(PaymentService::notFound);
        if (payment.getStatus() == PaymentStatus.CREATED) {
            PaymentStatus from = payment.transitionTo(PaymentStatus.PENDING, clock.instant());
            audit.record(paymentId, "AUTHORIZATION_STARTED", from, PaymentStatus.PENDING, source);
        }
        return payment;
    }

    private void requirePayable(Payment payment, Instant now) {
        if (payment.getStatus() != PaymentStatus.CREATED) {
            throw ApiException.conflict(payment.getStatus() == PaymentStatus.PENDING ? "PAYMENT_IN_PROGRESS" : "PAYMENT_ALREADY_PROCESSED",
                    "This payment is already " + payment.getStatus().name().toLowerCase().replace('_', ' ') + ".",
                    Map.of("paymentStatus", payment.getStatus().name()));
        }
        if (payment.isExpired(now)) {
            throw ApiException.conflict("PAYMENT_EXPIRED", "The payment window for this booking has closed.");
        }
    }

    private record Started(Payment payment, boolean newAttempt) {
    }

    /** Runs under the payment row lock, so concurrent confirmations are serialised. */
    private Started begin(UUID paymentId, String idempotencyKey, String token, UUID customerId, boolean viaCheckout) {
        Payment payment = payments.findByIdForUpdate(paymentId).orElseThrow(PaymentService::notFound);
        if (!payment.getCustomerId().equals(customerId)) {
            throw notFound(); // do not reveal that another customer's payment exists
        }
        if (attempts.findByPaymentIdAndIdempotencyKey(paymentId, idempotencyKey).isPresent()) {
            return new Started(payment, false);
        }
        Instant now = clock.instant();
        requirePayable(payment, now);
        if (viaCheckout && payment.getProviderOrderId() == null) {
            throw ApiException.conflict("CHECKOUT_NOT_STARTED", "Start the checkout for this payment first.");
        }
        attempts.save(new PaymentAttempt(paymentId, idempotencyKey, token, PaymentAttempt.Status.PROCESSING, now));
        PaymentStatus from = payment.transitionTo(PaymentStatus.PENDING, now);
        audit.record(paymentId, "AUTHORIZATION_STARTED", from, PaymentStatus.PENDING, "method " + token);
        return new Started(payment, true);
    }

    private void markAttempt(UUID paymentId, String key, PaymentAttempt.Status status, String reference, String failureCode) {
        attempts.findByPaymentIdAndIdempotencyKey(paymentId, key)
                .ifPresent(a -> a.complete(status, reference, failureCode, clock.instant()));
    }

    /**
     * Applies an authoritative provider outcome (response, webhook or reconciliation). Safe to call more
     * than once: once the payment has left PENDING, later outcomes are ignored.
     */
    @Transactional
    public Payment applyOutcome(UUID paymentId, ChargeResult result, String source) {
        Payment payment = payments.findByIdForUpdate(paymentId).orElseThrow(PaymentService::notFound);
        payment.recordProviderReference(result.providerReference());
        if (payment.getStatus() != PaymentStatus.PENDING || result.outcome() == PaymentProvider.Outcome.PENDING) {
            if (result.outcome() == PaymentProvider.Outcome.PENDING) {
                audit.record(paymentId, "PROVIDER_PENDING", payment.getStatus(), payment.getStatus(), source);
            }
            return payment;
        }
        Instant now = clock.instant();
        if (result.outcome() == PaymentProvider.Outcome.SUCCEEDED) {
            PaymentStatus from = payment.transitionTo(PaymentStatus.SUCCEEDED, now);
            audit.record(paymentId, "SUCCEEDED", from, PaymentStatus.SUCCEEDED, source + " " + result.providerReference());
            outbox.enqueue(OutboxService.PAYMENT_SUCCEEDED, payment);
            log.info("Payment {} for booking {} succeeded ({})", paymentId, payment.getBookingReference(), source);
        } else {
            payment.recordFailure(result.failureCode(), result.failureMessage());
            PaymentStatus from = payment.transitionTo(PaymentStatus.FAILED, now);
            audit.record(paymentId, "FAILED", from, PaymentStatus.FAILED, source + " " + result.failureCode());
            outbox.enqueue(OutboxService.PAYMENT_FAILED, payment);
            log.info("Payment {} for booking {} failed: {} ({})", paymentId, payment.getBookingReference(),
                    result.failureCode(), source);
        }
        return payment;
    }

    /**
     * Internal: void an unpaid payment (booking expired or cancelled before payment). Idempotent. A payment
     * already in progress or captured cannot be cancelled; the caller receives its current status instead.
     */
    @Transactional
    public Payment cancel(UUID paymentId, String reason) {
        Payment payment = payments.findByIdForUpdate(paymentId).orElseThrow(PaymentService::notFound);
        switch (payment.getStatus()) {
            case CANCELLED, FAILED -> {
                return payment;
            }
            case CREATED -> {
                PaymentStatus from = payment.transitionTo(PaymentStatus.CANCELLED, clock.instant());
                audit.record(paymentId, "CANCELLED", from, PaymentStatus.CANCELLED, reason);
                return payment;
            }
            default -> throw ApiException.conflict(payment.getStatus() == PaymentStatus.PENDING
                            ? "PAYMENT_IN_PROGRESS" : "PAYMENT_ALREADY_CAPTURED",
                    "Payment cannot be cancelled in status " + payment.getStatus() + ".",
                    Map.of("paymentStatus", payment.getStatus().name()));
        }
    }

    private Payment requireSameTerms(Payment existing, CreatePaymentRequest request) {
        if (existing.getAmount().compareTo(request.amount()) != 0 || !existing.getCurrency().equals(request.currency())
                || !existing.getCustomerId().equals(request.customerId())) {
            throw ApiException.conflict("PAYMENT_TERMS_MISMATCH", "A payment with different terms exists for this booking.");
        }
        return existing;
    }

    static void requireOwnerOrPersonnel(Payment payment, AuthenticatedUser caller) {
        if (caller.isPersonnel()) {
            return;
        }
        if (caller.id() == null || !caller.id().equals(payment.getCustomerId())) {
            throw notFound();
        }
    }

    static ApiException notFound() {
        return ApiException.notFound("PAYMENT_NOT_FOUND", "Payment not found.");
    }
}
