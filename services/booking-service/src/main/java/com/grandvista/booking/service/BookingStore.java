package com.grandvista.booking.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grandvista.booking.client.RoomServiceClient;
import com.grandvista.booking.domain.Booking;
import com.grandvista.booking.domain.BookingPriceSnapshot;
import com.grandvista.booking.domain.BookingStatus;
import com.grandvista.booking.domain.ProcessedEvent;
import com.grandvista.booking.repository.BookingRepository;
import com.grandvista.booking.repository.ProcessedEventRepository;
import com.grandvista.platform.error.ApiException;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Short, row-locked transactions used by the saga. Each step re-reads the booking under a lock and
 * re-checks its status, so concurrent actors (customer, payment events, recovery job) cannot apply
 * conflicting transitions. Network calls never happen inside these transactions.
 */
@Component
public class BookingStore {

    private final BookingRepository bookings;
    private final ProcessedEventRepository processedEvents;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public BookingStore(BookingRepository bookings, ProcessedEventRepository processedEvents, ObjectMapper objectMapper,
            Clock clock) {
        this.bookings = bookings;
        this.processedEvents = processedEvents;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Booking load(UUID bookingId) {
        return bookings.findById(bookingId).orElseThrow(BookingStore::notFound);
    }

    @Transactional(readOnly = true)
    public boolean referenceExists(String reference) {
        return bookings.existsByReference(reference);
    }

    @Transactional
    public Booking insert(Booking booking) {
        return bookings.saveAndFlush(booking);
    }

    @Transactional(readOnly = true)
    public Optional<Booking> findByIdempotencyKey(UUID customerId, String key) {
        return bookings.findByCustomerIdAndIdempotencyKey(customerId, key);
    }

    @Transactional
    public Booking attachHold(UUID bookingId, RoomServiceClient.Hold hold) {
        Booking b = lock(bookingId);
        var q = hold.quote();
        b.attachHold(hold.roomNumber(), hold.roomName(), hold.roomTypeName(), hold.holdExpiresAt(),
                new BookingPriceSnapshot(b, q.pricingPolicyVersion(), q.nights(), q.subtotal(), q.serviceFee(), q.taxes(),
                        q.total(), q.currency(), q.quotedAt(), toJson(q.nightlyRates())), clock.instant());
        return b;
    }

    @Transactional
    public Booking attachPaymentAndAwaitPayment(UUID bookingId, UUID paymentId, String actor) {
        Booking b = lock(bookingId);
        b.attachPayment(paymentId, clock.instant());
        if (b.getStatus() == BookingStatus.CREATED) {
            b.transitionTo(BookingStatus.PENDING_PAYMENT, "inventory held; awaiting payment", actor, clock.instant());
        }
        return b;
    }

    /**
     * Applies {@code target} only if the booking is currently in one of {@code expected}; otherwise returns
     * the booking unchanged (another actor got there first). Optionally records a processed event id.
     */
    @Transactional
    public Booking transitionIf(UUID bookingId, Set<BookingStatus> expected, BookingStatus target, String reason,
            String actor, BigDecimal refundAmount, ProcessedEventRef event) {
        Booking b = lock(bookingId);
        if (expected.contains(b.getStatus())) {
            if (refundAmount != null) {
                b.setRefundAmount(refundAmount);
            }
            b.transitionTo(target, reason, actor, clock.instant());
        }
        if (event != null && !processedEvents.existsById(event.eventId())) {
            processedEvents.save(new ProcessedEvent(event.eventId(), event.eventType(), bookingId, clock.instant()));
        }
        return b;
    }

    @Transactional
    public Booking transitionIf(UUID bookingId, BookingStatus expected, BookingStatus target, String reason, String actor) {
        return transitionIf(bookingId, EnumSet.of(expected), target, reason, actor, null, null);
    }

    @Transactional
    public void recordEvent(ProcessedEventRef event, UUID bookingId) {
        if (!processedEvents.existsById(event.eventId())) {
            processedEvents.save(new ProcessedEvent(event.eventId(), event.eventType(), bookingId, clock.instant()));
        }
    }

    @Transactional(readOnly = true)
    public boolean isProcessed(UUID eventId) {
        return processedEvents.existsById(eventId);
    }

    public record ProcessedEventRef(UUID eventId, String eventType) {
    }

    private Booking lock(UUID bookingId) {
        return bookings.findByIdForUpdate(bookingId).orElseThrow(BookingStore::notFound);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static ApiException notFound() {
        return ApiException.notFound("BOOKING_NOT_FOUND", "Booking not found.");
    }
}
