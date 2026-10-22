package com.grandvista.booking.repository;

import com.grandvista.booking.domain.Booking;
import com.grandvista.booking.domain.BookingStatus;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Optional search filters. Each factory returns {@code null} when its filter is absent, so only the
 * criteria actually supplied become SQL predicates (no "(:param IS NULL OR ...)" clauses, which
 * PostgreSQL cannot type when the parameter is null).
 */
public final class BookingSpecifications {

    private BookingSpecifications() {
    }

    @SafeVarargs
    public static Specification<Booking> allOf(Specification<Booking>... specs) {
        return Specification.allOf(Arrays.stream(specs).filter(Objects::nonNull).toList());
    }

    public static Specification<Booking> customer(UUID customerId) {
        return (root, query, cb) -> cb.equal(root.get("customerId"), customerId);
    }

    public static Specification<Booking> statusIn(Collection<BookingStatus> statuses) {
        return statuses == null || statuses.isEmpty() ? null : (root, query, cb) -> root.get("status").in(statuses);
    }

    public static Specification<Booking> status(BookingStatus status) {
        return status == null ? null : (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    /** Stay ends after the date (still in progress or upcoming). */
    public static Specification<Booking> checkOutAfter(LocalDate date) {
        return date == null ? null : (root, query, cb) -> cb.greaterThan(root.get("checkOut"), date);
    }

    public static Specification<Booking> checkOutOnOrBefore(LocalDate date) {
        return date == null ? null : (root, query, cb) -> cb.lessThanOrEqualTo(root.get("checkOut"), date);
    }

    public static Specification<Booking> checkInBefore(LocalDate date) {
        return date == null ? null : (root, query, cb) -> cb.lessThan(root.get("checkIn"), date);
    }

    /** Case-insensitive match on reference, customer e-mail or room number. */
    public static Specification<Booking> text(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        String pattern = "%" + q.trim().toLowerCase(Locale.ROOT).replace("%", "").replace("_", "") + "%";
        return (root, query, cb) -> cb.or(cb.like(cb.lower(root.get("reference")), pattern),
                cb.like(cb.lower(root.get("customerEmail")), pattern), cb.like(cb.lower(root.get("roomNumber")), pattern));
    }
}
