package com.grandvista.booking.service;

import com.grandvista.booking.client.DownstreamException;
import com.grandvista.booking.client.PaymentServiceClient;
import com.grandvista.booking.client.RoomServiceClient;
import com.grandvista.booking.config.BookingProperties;
import com.grandvista.booking.domain.Booking;
import com.grandvista.booking.domain.BookingStatus;
import com.grandvista.booking.domain.CancellationPolicy;
import com.grandvista.platform.client.ServiceCallException;
import com.grandvista.platform.error.ApiException;
import com.grandvista.platform.security.AuthenticatedUser;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Cancellation saga: CONFIRMED → CANCELLATION_PENDING (refund fixed by policy) → release inventory →
 * refund → CANCELLED. Both remote steps are idempotent, so an interrupted cancellation is simply resumed
 * by the recovery job until it completes.
 */
@Service
public class CancellationService {

    private static final Logger log = LoggerFactory.getLogger(CancellationService.class);

    private final BookingStore store;
    private final RoomServiceClient rooms;
    private final PaymentServiceClient payments;
    private final HotelCalendar calendar;
    private final CancellationPolicy policy;

    public CancellationService(BookingStore store, RoomServiceClient rooms, PaymentServiceClient payments,
            HotelCalendar calendar, BookingProperties properties) {
        this.store = store;
        this.rooms = rooms;
        this.payments = payments;
        this.calendar = calendar;
        this.policy = properties.cancellation().toPolicy();
    }

    public CancellationPolicy.Outcome preview(Booking booking, AuthenticatedUser user) {
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            return new CancellationPolicy.Outcome(booking.getStatus() == BookingStatus.PENDING_PAYMENT, 0,
                    BigDecimal.ZERO.setScale(2), 0, booking.getStatus() == BookingStatus.PENDING_PAYMENT
                    ? "NOT_YET_PAID" : "NOT_CANCELLABLE");
        }
        return policy.evaluate(booking.getTotalAmount(), booking.getCheckIn(), calendar.today(), isHotelInitiated(booking, user));
    }

    public Booking cancel(UUID bookingId, AuthenticatedUser user, String note) {
        Booking booking = store.load(bookingId);
        String actor = Actors.of(user);
        switch (booking.getStatus()) {
            case CANCELLED -> {
                return booking;
            }
            case CANCELLATION_PENDING -> {
                return complete(bookingId);
            }
            case PENDING_PAYMENT -> {
                return cancelUnpaid(booking, actor);
            }
            case CONFIRMED -> {
                CancellationPolicy.Outcome outcome = policy.evaluate(booking.getTotalAmount(), booking.getCheckIn(),
                        calendar.today(), isHotelInitiated(booking, user));
                if (!outcome.allowed()) {
                    throw ApiException.conflict("CANCELLATION_WINDOW_CLOSED",
                            "Bookings can be cancelled online only before the check-in date. Please contact the hotel.");
                }
                String reason = outcome.rule() + (note == null || note.isBlank() ? "" : ": " + note.trim());
                store.transitionIf(bookingId, EnumSet.of(BookingStatus.CONFIRMED), BookingStatus.CANCELLATION_PENDING,
                        reason, actor, outcome.refundAmount(), null);
                log.info("Booking {} cancellation started ({}, refund {})", booking.getReference(), outcome.rule(),
                        outcome.refundAmount());
                return complete(bookingId);
            }
            default -> throw ApiException.conflict("BOOKING_NOT_CANCELLABLE",
                    "A booking in status " + booking.getStatus() + " cannot be cancelled.");
        }
    }

    /** Booking not paid yet: void the payment first so it can no longer succeed, then free the room. */
    private Booking cancelUnpaid(Booking booking, String actor) {
        try {
            PaymentServiceClient.Payment payment = payments.cancel(booking.getPaymentId(), "BOOKING_CANCELLED");
            if (!"CANCELLED".equals(payment.status()) && !"FAILED".equals(payment.status())) {
                throw ApiException.conflict("PAYMENT_IN_PROGRESS", "A payment is in progress for this booking.");
            }
        } catch (DownstreamException ex) {
            if ("PAYMENT_IN_PROGRESS".equals(ex.code())) {
                throw ApiException.conflict("PAYMENT_IN_PROGRESS",
                        "A payment is being processed for this booking. Please try again in a moment.");
            }
            if ("PAYMENT_ALREADY_CAPTURED".equals(ex.code())) {
                throw ApiException.conflict("PAYMENT_ALREADY_COMPLETED",
                        "Your payment has completed and the booking is being confirmed. You can cancel it once confirmed.");
            }
            throw ex;
        } catch (ServiceCallException ex) {
            throw ApiException.unavailable("PAYMENT_SERVICE_UNAVAILABLE", "Cancellation is temporarily unavailable.");
        }
        try {
            rooms.release(booking.getId(), "CANCELLED_BEFORE_PAYMENT");
        } catch (ServiceCallException ex) {
            log.warn("Hold release for {} failed; the hold will expire on its own", booking.getReference());
        }
        return store.transitionIf(booking.getId(), BookingStatus.PENDING_PAYMENT, BookingStatus.CANCELLED,
                "CANCELLED_BEFORE_PAYMENT", actor);
    }

    /** Idempotent completion of a pending cancellation (also driven by the recovery job). */
    public Booking complete(UUID bookingId) {
        Booking booking = store.load(bookingId);
        if (booking.getStatus() != BookingStatus.CANCELLATION_PENDING) {
            return booking;
        }
        try {
            rooms.release(bookingId, "CANCELLED");
            BigDecimal refund = booking.getRefundAmount() == null ? BigDecimal.ZERO : booking.getRefundAmount();
            if (refund.signum() > 0) {
                PaymentServiceClient.Refund result = payments.refund(booking.getPaymentId(),
                        new PaymentServiceClient.RefundRequest(refund, refundReason(booking), "refund-" + bookingId));
                if ("FAILED".equals(result.status())) {
                    return store.transitionIf(bookingId, BookingStatus.CANCELLATION_PENDING, BookingStatus.NEEDS_ATTENTION,
                            "REFUND_FAILED", Actors.RECOVERY);
                }
                if (!"SUCCEEDED".equals(result.status())) {
                    log.info("Refund for {} still pending; will retry", booking.getReference());
                    return booking;
                }
            }
            Booking done = store.transitionIf(bookingId, BookingStatus.CANCELLATION_PENDING, BookingStatus.CANCELLED,
                    booking.getStatusReason(), "system:cancellation");
            log.info("Booking {} cancelled; refunded {}", done.getReference(), refund);
            return done;
        } catch (ServiceCallException ex) {
            log.warn("Cancellation of {} not finished ({}); recovery will retry", booking.getReference(), ex.getMessage());
            return booking;
        } catch (DownstreamException ex) {
            log.error("Cancellation of {} rejected by {}: {}", booking.getReference(), ex.getService(), ex.code());
            return store.transitionIf(bookingId, BookingStatus.CANCELLATION_PENDING, BookingStatus.NEEDS_ATTENTION,
                    ex.getService().toUpperCase().replace('-', '_') + "_" + ex.code(), Actors.RECOVERY);
        }
    }

    /** Operations: retry a compensation that needed attention (ADMIN). */
    public Booking retryCompensation(UUID bookingId, AuthenticatedUser admin) {
        Booking b = store.transitionIf(bookingId, BookingStatus.NEEDS_ATTENTION, BookingStatus.CANCELLATION_PENDING,
                "retry requested", Actors.of(admin));
        if (b.getStatus() != BookingStatus.CANCELLATION_PENDING) {
            throw ApiException.conflict("NOT_NEEDING_ATTENTION", "This booking does not need attention.",
                    Map.of("status", b.getStatus().name()));
        }
        return complete(bookingId);
    }

    private static String refundReason(Booking booking) {
        String reason = booking.getStatusReason() == null ? "CANCELLATION" : booking.getStatusReason();
        String code = reason.split(":")[0].trim().toUpperCase().replaceAll("[^A-Z_]", "_");
        return code.length() < 3 ? "CANCELLATION" : code.substring(0, Math.min(60, code.length()));
    }

    private static boolean isHotelInitiated(Booking booking, AuthenticatedUser user) {
        return user.isPersonnel() && (user.id() == null || !booking.isOwnedBy(user.id()));
    }
}
