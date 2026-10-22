package com.grandvista.booking.service;

import com.grandvista.booking.client.DownstreamException;
import com.grandvista.booking.client.PaymentServiceClient;
import com.grandvista.booking.client.RoomServiceClient;
import com.grandvista.booking.domain.Booking;
import com.grandvista.booking.domain.BookingStatus;
import com.grandvista.booking.web.dto.BookingDtos.PaymentEventMessage;
import com.grandvista.platform.error.ApiException;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Idempotent consumer of payment events (delivered at-least-once by the payment-service outbox).
 * Remote calls are made first and the state change + inbox record are committed together afterwards, so a
 * failure part-way leaves the event unrecorded and the producer's retry repeats the (idempotent) work.
 * Transport failures propagate as 503, which tells the outbox relay to retry later.
 */
@Service
public class PaymentEventService {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventService.class);
    private static final Set<BookingStatus> ALREADY_SETTLED = EnumSet.of(BookingStatus.CONFIRMED,
            BookingStatus.CANCELLATION_PENDING, BookingStatus.CANCELLED, BookingStatus.NEEDS_ATTENTION);

    public enum Result { APPLIED, DUPLICATE, IGNORED }

    private final BookingStore store;
    private final RoomServiceClient rooms;
    private final PaymentServiceClient payments;
    private final CancellationService cancellations;

    public PaymentEventService(BookingStore store, RoomServiceClient rooms, PaymentServiceClient payments,
            CancellationService cancellations) {
        this.store = store;
        this.rooms = rooms;
        this.payments = payments;
        this.cancellations = cancellations;
    }

    public Result handle(PaymentEventMessage event) {
        if (store.isProcessed(event.eventId())) {
            return Result.DUPLICATE;
        }
        Booking booking;
        try {
            booking = store.load(event.bookingId());
        } catch (ApiException notFound) {
            log.error("Payment event {} references unknown booking {}; ignored", event.eventId(), event.bookingId());
            return Result.IGNORED;
        }
        if (booking.getPaymentId() != null && !booking.getPaymentId().equals(event.paymentId())) {
            log.error("Payment event {} does not match booking {}'s payment; ignored", event.eventId(), booking.getReference());
            return Result.IGNORED;
        }
        var ref = new BookingStore.ProcessedEventRef(event.eventId(), event.eventType());
        return switch (event.eventType()) {
            case "PAYMENT_SUCCEEDED" -> {
                onPaymentSucceeded(booking, event.paymentId(), event.amount(), ref);
                yield Result.APPLIED;
            }
            case "PAYMENT_FAILED" -> {
                onPaymentFailed(booking, event.failureCode(), ref);
                yield Result.APPLIED;
            }
            default -> {
                store.recordEvent(ref, booking.getId());
                yield Result.IGNORED;
            }
        };
    }

    /** Payment captured: confirm the inventory; if the room was lost meanwhile, compensate with a refund. */
    /**
     * @param paymentId the captured payment (from the event; the booking may not reference it yet)
     * @param amount    the captured amount, refunded in full if the booking is no longer active
     */
    public void onPaymentSucceeded(Booking booking, UUID paymentId, java.math.BigDecimal amount,
            BookingStore.ProcessedEventRef event) {
        UUID id = booking.getId();
        if (ALREADY_SETTLED.contains(booking.getStatus())) {
            if (event != null) {
                store.recordEvent(event, id);
            }
            return;
        }
        if (booking.getStatus() != BookingStatus.PENDING_PAYMENT) {
            // Defensive: unpaid bookings only expire/cancel after their payment was voided, so a capture should
            // never arrive here. If one does, the money is returned and the booking stays in its final state.
            log.error("Payment captured for booking {} in status {}; issuing a full refund", booking.getReference(),
                    booking.getStatus());
            payments.refund(paymentId, new PaymentServiceClient.RefundRequest(amount, "BOOKING_NOT_ACTIVE",
                    "refund-orphan-" + id));
            if (event != null) {
                store.recordEvent(event, id);
            }
            return;
        }
        try {
            rooms.confirm(id);
        } catch (DownstreamException ex) {
            log.warn("Booking {} paid but inventory could not be confirmed ({}); refunding in full",
                    booking.getReference(), ex.code());
            store.transitionIf(id, EnumSet.of(BookingStatus.PENDING_PAYMENT), BookingStatus.CANCELLATION_PENDING,
                    "INVENTORY_LOST_AFTER_PAYMENT", Actors.PAYMENT_EVENTS, booking.getTotalAmount(), event);
            cancellations.complete(id);
            return;
        }
        Booking confirmed = store.transitionIf(id, EnumSet.of(BookingStatus.PENDING_PAYMENT), BookingStatus.CONFIRMED,
                "payment succeeded", Actors.PAYMENT_EVENTS, null, event);
        log.info("Booking {} is {}", confirmed.getReference(), confirmed.getStatus());
    }

    public void onPaymentFailed(Booking booking, String failureCode, BookingStore.ProcessedEventRef event) {
        if (booking.getStatus() == BookingStatus.PENDING_PAYMENT) {
            rooms.release(booking.getId(), "PAYMENT_FAILED");
        }
        store.transitionIf(booking.getId(), EnumSet.of(BookingStatus.PENDING_PAYMENT), BookingStatus.PAYMENT_FAILED,
                failureCode == null ? "payment failed" : "payment failed: " + failureCode, Actors.PAYMENT_EVENTS, null, event);
    }
}
