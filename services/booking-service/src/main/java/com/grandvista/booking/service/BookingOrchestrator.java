package com.grandvista.booking.service;

import com.grandvista.booking.client.DownstreamException;
import com.grandvista.booking.client.PaymentServiceClient;
import com.grandvista.booking.client.RoomServiceClient;
import com.grandvista.booking.domain.Booking;
import com.grandvista.booking.domain.BookingStatus;
import com.grandvista.booking.web.dto.BookingDtos.CreateBookingRequest;
import com.grandvista.platform.client.ServiceCallException;
import com.grandvista.platform.error.ApiException;
import com.grandvista.platform.security.AuthenticatedUser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Orchestrates booking creation as a saga (no distributed transaction):
 * <ol>
 *   <li>record the request idempotently (customer + Idempotency-Key) in state CREATED</li>
 *   <li>room-service: place a temporary hold and obtain the authoritative price</li>
 *   <li>verify the price the customer saw; store an immutable price snapshot</li>
 *   <li>payment-service: create the payment for exactly that amount, expiring with the hold</li>
 *   <li>move to PENDING_PAYMENT and return; confirmation arrives later as a payment event</li>
 * </ol>
 * Every remote step is idempotent, so a retried request resumes where the previous one stopped. If a step
 * fails definitively the hold is released (compensation) and the booking is REJECTED; if a dependency is
 * unreachable the booking stays CREATED and the recovery job cleans it up — the customer is never charged.
 */
@Service
public class BookingOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(BookingOrchestrator.class);
    private static final Set<String> DATE_ERRORS = Set.of("INVALID_DATE_RANGE", "CHECK_IN_IN_PAST", "STAY_TOO_LONG",
            "BOOKING_WINDOW_EXCEEDED", "DATES_REQUIRED");

    private final BookingStore store;
    private final RoomServiceClient rooms;
    private final PaymentServiceClient payments;
    private final ReferenceGenerator references;
    private final HotelCalendar calendar;
    private final Clock clock;

    public BookingOrchestrator(BookingStore store, RoomServiceClient rooms, PaymentServiceClient payments,
            ReferenceGenerator references, HotelCalendar calendar, Clock clock) {
        this.store = store;
        this.rooms = rooms;
        this.payments = payments;
        this.references = references;
        this.calendar = calendar;
        this.clock = clock;
    }

    public record Result(Booking booking, boolean created) {
    }

    public Result create(CreateBookingRequest request, String idempotencyKey, AuthenticatedUser user) {
        UUID customerId = user.requireUserId();
        calendar.validateStay(request.checkIn(), request.checkOut());
        String fingerprint = fingerprint(request);

        var existing = store.findByIdempotencyKey(customerId, idempotencyKey);
        if (existing.isPresent()) {
            Booking b = requireSameRequest(existing.get(), fingerprint);
            return new Result(b.getStatus() == BookingStatus.CREATED ? proceed(b.getId(), request.expectedTotal(), user) : b,
                    false);
        }
        Booking booking;
        try {
            booking = store.insert(newBooking(request, idempotencyKey, fingerprint, user));
        } catch (DataIntegrityViolationException race) {
            // A concurrent request with the same key won the insert: behave as a replay.
            Booking b = store.findByIdempotencyKey(customerId, idempotencyKey).orElseThrow(() -> race);
            return new Result(requireSameRequest(b, fingerprint), false);
        }
        log.info("Booking {} requested by {} for room {} {}..{}", booking.getReference(), customerId, request.roomId(),
                request.checkIn(), request.checkOut());
        return new Result(proceed(booking.getId(), request.expectedTotal(), user), true);
    }

    private Booking proceed(UUID bookingId, BigDecimal expectedTotal, AuthenticatedUser user) {
        Booking booking = store.load(bookingId);
        String actor = Actors.of(user);

        // Step 2: inventory hold + authoritative price
        RoomServiceClient.Hold hold;
        try {
            hold = rooms.hold(new RoomServiceClient.HoldRequest(booking.getId(), booking.getRoomId(), booking.getCheckIn(),
                    booking.getCheckOut(), booking.getGuests()));
        } catch (DownstreamException ex) {
            store.transitionIf(bookingId, BookingStatus.CREATED, BookingStatus.REJECTED, ex.code(), actor);
            throw rejection(booking, ex);
        } catch (ServiceCallException ex) {
            log.warn("Booking {}: inventory unavailable, left CREATED for retry/recovery: {}", booking.getReference(),
                    ex.getMessage());
            throw unavailable(booking, "INVENTORY_SERVICE_UNAVAILABLE");
        }
        if (!"HELD".equals(hold.status())) {
            store.transitionIf(bookingId, BookingStatus.CREATED, BookingStatus.REJECTED, "HOLD_" + hold.status(), actor);
            throw ApiException.conflict("HOLD_NO_LONGER_VALID", "The reservation hold expired. Please start a new booking.",
                    Map.of("bookingId", bookingId));
        }

        // Step 3: the customer must never be charged a price they did not see
        BigDecimal total = hold.quote().total();
        if (expectedTotal != null && total.compareTo(expectedTotal) != 0) {
            releaseQuietly(bookingId, "PRICE_CHANGED");
            store.transitionIf(bookingId, BookingStatus.CREATED, BookingStatus.REJECTED, "PRICE_CHANGED", actor);
            throw ApiException.conflict("PRICE_CHANGED", "The price for these dates has changed. Please review the new price.",
                    Map.of("bookingId", bookingId, "previousTotal", expectedTotal, "currentTotal", total,
                            "currency", hold.quote().currency()));
        }
        booking = store.attachHold(bookingId, hold);

        // Step 4: payment for exactly the snapshot amount, valid only while the hold lasts
        PaymentServiceClient.Payment payment;
        try {
            payment = payments.create(new PaymentServiceClient.CreatePayment(bookingId, booking.getReference(),
                    booking.getCustomerId(), booking.getTotalAmount(), booking.getCurrency(), hold.holdExpiresAt()));
        } catch (DownstreamException ex) {
            releaseQuietly(bookingId, "PAYMENT_SETUP_FAILED");
            store.transitionIf(bookingId, BookingStatus.CREATED, BookingStatus.REJECTED, "PAYMENT_" + ex.code(), actor);
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PAYMENT_SETUP_FAILED",
                    "The payment could not be prepared. You have not been charged.", Map.of("bookingId", bookingId));
        } catch (ServiceCallException ex) {
            log.warn("Booking {}: payment service unavailable, left CREATED for retry/recovery", booking.getReference());
            throw unavailable(booking, "PAYMENT_SERVICE_UNAVAILABLE");
        }

        // Step 5
        Booking pending = store.attachPaymentAndAwaitPayment(bookingId, payment.id(), actor);
        log.info("Booking {} awaiting payment {} (hold until {})", pending.getReference(), payment.id(),
                pending.getHoldExpiresAt());
        return pending;
    }

    private void releaseQuietly(UUID bookingId, String reason) {
        try {
            rooms.release(bookingId, reason);
        } catch (RuntimeException ex) {
            // The hold also expires on its own after its TTL; recovery logs make this visible.
            log.warn("Could not release hold for booking {} ({}); it will expire automatically", bookingId, ex.getMessage());
        }
    }

    private Booking newBooking(CreateBookingRequest r, String key, String fingerprint, AuthenticatedUser user) {
        String reference = references.next();
        // ~887 million combinations; the unique constraint remains the final guard.
        for (int i = 0; i < 5 && store.referenceExists(reference); i++) {
            reference = references.next();
        }
        Booking booking = new Booking(reference, user.requireUserId(), user.email() == null ? r.primaryGuest().email() : user.email(),
                r.roomId(), r.checkIn(), r.checkOut(), r.guests(), key, fingerprint,
                r.specialRequests() == null || r.specialRequests().isBlank() ? null : r.specialRequests().trim(),
                clock.instant());
        booking.addGuest(r.primaryGuest().fullName().trim(), r.primaryGuest().email().trim().toLowerCase(Locale.ROOT),
                r.primaryGuest().phone(), true);
        return booking;
    }

    private static Booking requireSameRequest(Booking existing, String fingerprint) {
        if (!existing.getRequestFingerprint().equals(fingerprint)) {
            throw ApiException.unprocessable("IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used for a different booking request.");
        }
        return existing;
    }

    static String fingerprint(CreateBookingRequest r) {
        String canonical = String.join("|", r.roomId().toString(), r.checkIn().toString(), r.checkOut().toString(),
                r.guests().toString(), r.expectedTotal() == null ? "-" : r.expectedTotal().stripTrailingZeros().toPlainString(),
                r.primaryGuest().fullName().trim(), r.primaryGuest().email().trim().toLowerCase(Locale.ROOT));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static ApiException rejection(Booking booking, DownstreamException ex) {
        Map<String, Object> props = Map.of("bookingId", booking.getId(), "bookingReference", booking.getReference());
        String code = ex.code();
        if ("ROOM_UNAVAILABLE".equals(code) || "ROOM_NOT_AVAILABLE".equals(code)) {
            return ApiException.conflict("ROOM_UNAVAILABLE",
                    "Sorry, this room is no longer available for the selected dates. You have not been charged.", props);
        }
        if ("GUEST_COUNT_EXCEEDS_CAPACITY".equals(code)) {
            return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, ex.getProblem().detail(), props);
        }
        if ("ROOM_NOT_FOUND".equals(code)) {
            return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, "The selected room does not exist.", props);
        }
        if (DATE_ERRORS.contains(code)) {
            return new ApiException(HttpStatus.BAD_REQUEST, code, ex.getProblem().detail(), props);
        }
        return ApiException.conflict("BOOKING_REJECTED", "The booking could not be completed. You have not been charged.", props);
    }

    private static ApiException unavailable(Booking booking, String code) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, code,
                "We could not complete your booking right now. You have not been charged; please retry shortly.",
                Map.of("bookingId", booking.getId(), "bookingReference", booking.getReference(), "retryable", true));
    }
}
