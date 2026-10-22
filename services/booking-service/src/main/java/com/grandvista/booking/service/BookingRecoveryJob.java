package com.grandvista.booking.service;

import com.grandvista.booking.client.DownstreamException;
import com.grandvista.booking.client.PaymentServiceClient;
import com.grandvista.booking.client.RoomServiceClient;
import com.grandvista.booking.config.BookingProperties;
import com.grandvista.booking.domain.Booking;
import com.grandvista.booking.domain.BookingStatus;
import com.grandvista.booking.repository.BookingRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives every booking that stopped mid-saga to a final state:
 * <ul>
 *   <li>CREATED for too long (a dependency was down): release any hold, void any payment → EXPIRED</li>
 *   <li>PENDING_PAYMENT past the hold expiry: void the payment; if that succeeds → EXPIRED and release the
 *       room; if the payment had in fact succeeded (lost event) → confirm via the normal success path</li>
 *   <li>CANCELLATION_PENDING: resume release + refund</li>
 * </ul>
 * Safe with several instances: every transition is a locked compare-and-set in {@link BookingStore}.
 */
@Component
public class BookingRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(BookingRecoveryJob.class);
    private static final int BATCH = 50;

    private final BookingRepository bookings;
    private final BookingStore store;
    private final RoomServiceClient rooms;
    private final PaymentServiceClient payments;
    private final PaymentEventService paymentEvents;
    private final CancellationService cancellations;
    private final BookingProperties properties;
    private final Clock clock;

    public BookingRecoveryJob(BookingRepository bookings, BookingStore store, RoomServiceClient rooms,
            PaymentServiceClient payments, PaymentEventService paymentEvents, CancellationService cancellations,
            BookingProperties properties, Clock clock) {
        this.bookings = bookings;
        this.store = store;
        this.rooms = rooms;
        this.payments = payments;
        this.paymentEvents = paymentEvents;
        this.cancellations = cancellations;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${gv.booking.recovery.interval:30s}", initialDelayString = "${gv.booking.recovery.initial-delay:30s}")
    public void run() {
        Instant now = clock.instant();
        Instant stuckBefore = now.minus(properties.recovery().stuckThreshold());
        bookings.findIdsByStatusUpdatedBefore(BookingStatus.CREATED, stuckBefore, PageRequest.of(0, BATCH))
                .forEach(id -> safely("stuck CREATED", id, () -> recoverCreated(id)));
        bookings.findExpiredPendingPaymentIds(now.minus(properties.recovery().expiryGrace()), PageRequest.of(0, BATCH))
                .forEach(id -> safely("unpaid expiry", id, () -> expireUnpaid(id)));
        bookings.findIdsByStatusUpdatedBefore(BookingStatus.CANCELLATION_PENDING, stuckBefore, PageRequest.of(0, BATCH))
                .forEach(id -> safely("pending cancellation", id, () -> cancellations.complete(id)));
    }

    public void recoverCreated(UUID id) {
        Booking b = store.load(id);
        if (b.getStatus() != BookingStatus.CREATED) {
            return;
        }
        try {
            PaymentServiceClient.Payment payment = payments.getByBooking(id);
            payments.cancel(payment.id(), "BOOKING_NOT_COMPLETED");
        } catch (DownstreamException ex) {
            if (!"PAYMENT_NOT_FOUND".equals(ex.code())) {
                log.error("Stuck booking {}: payment could not be voided ({}); leaving for investigation",
                        b.getReference(), ex.code());
                return;
            }
        }
        rooms.release(id, "BOOKING_NOT_COMPLETED");
        store.transitionIf(id, BookingStatus.CREATED, BookingStatus.EXPIRED, "ORCHESTRATION_TIMEOUT", Actors.RECOVERY);
        log.info("Recovered stuck booking {} -> EXPIRED", b.getReference());
    }

    public void expireUnpaid(UUID id) {
        Booking b = store.load(id);
        if (b.getStatus() != BookingStatus.PENDING_PAYMENT) {
            return;
        }
        PaymentServiceClient.Payment payment;
        try {
            payment = payments.cancel(b.getPaymentId(), "BOOKING_EXPIRED");
        } catch (DownstreamException ex) {
            if ("PAYMENT_ALREADY_CAPTURED".equals(ex.code())) {
                log.warn("Booking {}: payment succeeded but no event was applied; confirming now", b.getReference());
                paymentEvents.onPaymentSucceeded(b, b.getPaymentId(), b.getTotalAmount(), null);
            } else {
                log.info("Booking {}: payment still in progress ({}); will check again", b.getReference(), ex.code());
            }
            return;
        }
        rooms.release(id, "PAYMENT_WINDOW_ELAPSED");
        BookingStatus target = "FAILED".equals(payment.status()) ? BookingStatus.PAYMENT_FAILED : BookingStatus.EXPIRED;
        store.transitionIf(id, BookingStatus.PENDING_PAYMENT, target, "PAYMENT_WINDOW_ELAPSED", Actors.RECOVERY);
        log.info("Booking {} -> {} (payment window elapsed)", b.getReference(), target);
    }

    private void safely(String what, UUID id, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ex) {
            log.warn("Recovery ({}) of booking {} failed; will retry: {}", what, id, ex.getMessage());
        }
    }
}
