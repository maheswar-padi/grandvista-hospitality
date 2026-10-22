package com.grandvista.booking.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grandvista.booking.domain.Booking;
import com.grandvista.booking.domain.BookingGuest;
import com.grandvista.booking.domain.BookingStatus;
import com.grandvista.booking.domain.OutboxEvent;
import com.grandvista.booking.repository.OutboxEventRepository;
import com.grandvista.platform.messaging.EventTopology;
import com.grandvista.platform.web.CorrelationId;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes a booking domain event to the outbox in the same transaction as the status change that caused it
 * (transactional outbox), so an event is published if and only if the change was committed.
 */
@Component
public class BookingEventRecorder {

    /**
     * Versioned event contract (schemaVersion 1) published to {@code grandvista.events}. Every booking event
     * carries the full contact and stay details, so consumers never need to call the booking-service and do
     * not depend on the order in which events arrive.
     */
    public record BookingEventMessage(UUID eventId, String eventType, int schemaVersion, UUID bookingId, String reference,
                                      UUID customerId, String customerEmail, String guestName, String roomName,
                                      String roomTypeName, String roomNumber, LocalDate checkIn, LocalDate checkOut,
                                      int nights, int guests, BigDecimal totalAmount, String currency,
                                      BigDecimal refundAmount, Instant holdExpiresAt, String status, String reason,
                                      Instant occurredAt) {
    }

    private final OutboxEventRepository outbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public BookingEventRecorder(OutboxEventRepository outbox, ObjectMapper objectMapper, Clock clock) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** The event published when a booking enters {@code status}, or null when that status has no event. */
    static String eventTypeFor(BookingStatus status) {
        return switch (status) {
            case PENDING_PAYMENT -> EventTopology.BOOKING_CREATED;
            case CONFIRMED -> EventTopology.BOOKING_CONFIRMED;
            case CANCELLED -> EventTopology.BOOKING_CANCELLED;
            case EXPIRED -> EventTopology.HOLD_EXPIRED;
            default -> null;
        };
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void onTransition(Booking booking) {
        String type = eventTypeFor(booking.getStatus());
        if (type == null) {
            return;
        }
        UUID eventId = UUID.randomUUID();
        Instant now = clock.instant();
        String guestName = booking.getGuestsList().stream().filter(BookingGuest::isPrimary).map(BookingGuest::getFullName)
                .findFirst().orElse(null);
        var message = new BookingEventMessage(eventId, type, 1, booking.getId(), booking.getReference(),
                booking.getCustomerId(), booking.getCustomerEmail(), guestName, booking.getRoomName(),
                booking.getRoomTypeName(), booking.getRoomNumber(), booking.getCheckIn(), booking.getCheckOut(),
                booking.getNights(), booking.getGuests(), booking.getTotalAmount(), booking.getCurrency(),
                booking.getRefundAmount(), booking.getHoldExpiresAt(), booking.getStatus().name(),
                booking.getStatusReason(), now);
        try {
            outbox.save(new OutboxEvent(eventId, booking.getId(), type, objectMapper.writeValueAsString(message),
                    CorrelationId.current().orElse(null), now));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
