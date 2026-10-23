package com.grandvista.booking.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grandvista.booking.config.BookingProperties;
import com.grandvista.booking.domain.Booking;
import com.grandvista.booking.domain.BookingGuest;
import com.grandvista.booking.domain.BookingPriceSnapshot;
import com.grandvista.booking.domain.BookingStatus;
import com.grandvista.booking.repository.BookingRepository;
import com.grandvista.booking.repository.BookingSpecifications;
import com.grandvista.booking.web.dto.BookingDtos.BookingResponse;
import com.grandvista.booking.web.dto.BookingDtos.BookingSummary;
import com.grandvista.booking.web.dto.BookingDtos.DailyCount;
import com.grandvista.booking.web.dto.BookingDtos.GuestResponse;
import com.grandvista.booking.web.dto.BookingDtos.HistoryEntry;
import com.grandvista.booking.web.dto.BookingDtos.PriceResponse;
import com.grandvista.booking.web.dto.BookingDtos.RoomSummary;
import com.grandvista.booking.web.dto.BookingDtos.StatusCount;
import com.grandvista.platform.error.ApiException;
import com.grandvista.platform.security.AuthenticatedUser;
import com.grandvista.platform.web.PageResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingQueryService {

    public enum Scope { UPCOMING, PAST, ALL }

    private final BookingRepository bookings;
    private final HotelCalendar calendar;
    private final BookingProperties properties;
    private final ObjectMapper objectMapper;

    public BookingQueryService(BookingRepository bookings, HotelCalendar calendar, BookingProperties properties,
            ObjectMapper objectMapper) {
        this.bookings = bookings;
        this.calendar = calendar;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** Owners see their bookings; staff/admins see all. Others get 404 so ids cannot be probed. */
    @Transactional(readOnly = true)
    public Booking getForCaller(UUID bookingId, AuthenticatedUser user) {
        Booking booking = bookings.findById(bookingId).orElseThrow(BookingStore::notFound);
        if (!user.isPersonnel() && (user.id() == null || !booking.isOwnedBy(user.id()))) {
            throw BookingStore.notFound();
        }
        return booking;
    }

    @Transactional(readOnly = true)
    public BookingResponse view(UUID bookingId, AuthenticatedUser user) {
        return toResponse(getForCaller(bookingId, user));
    }

    @Transactional(readOnly = true)
    public List<HistoryEntry> history(UUID bookingId, AuthenticatedUser user) {
        return getForCaller(bookingId, user).getHistory().stream()
                .map(h -> new HistoryEntry(h.getFromStatus(), h.getToStatus(), h.getReason(), h.getActor(), h.getOccurredAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<BookingResponse> mine(AuthenticatedUser user, Scope scope, int page, int size) {
        LocalDate today = calendar.today();
        var visible = EnumSet.complementOf(EnumSet.of(BookingStatus.CREATED));
        var mine = BookingSpecifications.allOf(BookingSpecifications.customer(user.requireUserId()),
                BookingSpecifications.statusIn(visible));
        var result = switch (scope) {
            case UPCOMING -> bookings.findAll(mine.and(BookingSpecifications.checkOutAfter(today)),
                    PageRequest.of(page, size, Sort.by("checkIn").ascending()));
            case PAST -> bookings.findAll(mine.and(BookingSpecifications.checkOutOnOrBefore(today)),
                    PageRequest.of(page, size, Sort.by("checkIn").descending()));
            case ALL -> bookings.findAll(mine, PageRequest.of(page, size, Sort.by("createdAt").descending()));
        };
        return PageResponse.of(result, this::toResponse);
    }

    @Transactional(readOnly = true)
    public PageResponse<BookingResponse> adminSearch(UUID propertyId, BookingStatus status, LocalDate from, LocalDate to,
            String q, int page, int size) {
        var filter = BookingSpecifications.allOf(BookingSpecifications.property(propertyId),
                BookingSpecifications.status(status),
                BookingSpecifications.checkOutAfter(from), BookingSpecifications.checkInBefore(to),
                BookingSpecifications.text(q));
        return PageResponse.of(bookings.findAll(filter, PageRequest.of(page, size, Sort.by("createdAt").descending())),
                this::toResponse);
    }

    @Transactional(readOnly = true)
    public BookingSummary summary() {
        List<StatusCount> byStatus = bookings.countByStatus().stream()
                .map(s -> new StatusCount(s.getStatus(), s.getCount(), s.getAmount())).toList();
        BigDecimal revenue = byStatus.stream().filter(s -> s.status() == BookingStatus.CONFIRMED)
                .map(StatusCount::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        long attention = byStatus.stream().filter(s -> s.status() == BookingStatus.NEEDS_ATTENTION)
                .mapToLong(StatusCount::count).sum();
        LocalDate today = calendar.today();
        List<DailyCount> daily = new ArrayList<>();
        bookings.dailyCounts(today.minusDays(29), today, properties.hotelZone().getId())
                .forEach(d -> daily.add(new DailyCount(d.getDay().toLocalDate(), d.getCreated(), d.getConfirmed())));
        return new BookingSummary(byStatus, revenue, bookings.countByStatusOnCheckInDate(BookingStatus.CONFIRMED, today),
                attention, daily);
    }

    BookingResponse toResponse(Booking b) {
        BookingGuest guest = b.getGuestsList().stream().filter(BookingGuest::isPrimary).findFirst().orElse(null);
        BookingPriceSnapshot p = b.getPriceSnapshot();
        PriceResponse price = p == null ? null : new PriceResponse(p.getNights(), p.getSubtotal(), p.getServiceFee(),
                p.getTaxes(), p.getTotal(), p.getCurrency(), p.getPricingPolicyVersion(), p.getQuotedAt(),
                parse(p.getNightlyRates()));
        List<String> actions = new ArrayList<>();
        if (b.getStatus() == BookingStatus.PENDING_PAYMENT) {
            actions.add("PAY");
            actions.add("CANCEL");
        } else if (b.getStatus() == BookingStatus.CONFIRMED && b.getCheckIn().isAfter(calendar.today())) {
            actions.add("CANCEL");
        }
        return new BookingResponse(b.getId(), b.getReference(), b.getStatus(), b.getStatusReason(),
                new RoomSummary(b.getRoomId(), b.getRoomNumber(), b.getRoomName(), b.getRoomTypeName(), b.getPropertyId(),
                        b.getPropertyName()),
                b.getCheckIn(), b.getCheckOut(), b.getNights(), b.getGuests(),
                guest == null ? null : new GuestResponse(guest.getFullName(), guest.getEmail(), guest.getPhone()),
                b.getSpecialRequests(), price, b.getPaymentId(), b.getHoldExpiresAt(), b.getRefundAmount(), actions,
                b.getCreatedAt(), b.getConfirmedAt(), b.getCancelledAt(), b.getCustomerId(), b.getCustomerEmail());
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
