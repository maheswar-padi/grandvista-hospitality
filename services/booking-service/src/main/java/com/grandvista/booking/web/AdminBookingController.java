package com.grandvista.booking.web;

import com.grandvista.booking.domain.BookingStatus;
import com.grandvista.booking.service.BookingQueryService;
import com.grandvista.booking.service.CancellationService;
import com.grandvista.booking.web.dto.BookingDtos.BookingResponse;
import com.grandvista.booking.web.dto.BookingDtos.BookingSummary;
import com.grandvista.platform.security.AuthenticatedUser;
import com.grandvista.platform.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/bookings")
@Validated
@Tag(name = "Booking administration")
@SecurityRequirement(name = "bearer-jwt")
public class AdminBookingController {

    private final BookingQueryService queries;
    private final CancellationService cancellations;

    public AdminBookingController(BookingQueryService queries, CancellationService cancellations) {
        this.queries = queries;
        this.cancellations = cancellations;
    }

    @Operation(summary = "Search bookings by status, stay window, reference, e-mail or room (STAFF, ADMIN)")
    @GetMapping
    public PageResponse<BookingResponse> search(@RequestParam(required = false) java.util.UUID propertyId,
            @RequestParam(required = false) BookingStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(200) int size) {
        return queries.adminSearch(propertyId, status, from, to, q, page, size);
    }

    @Operation(summary = "Counts by status, confirmed revenue, arrivals today and 30-day trend (STAFF, ADMIN)")
    @GetMapping("/summary")
    public BookingSummary summary() {
        return queries.summary();
    }

    @Operation(summary = "Retry a failed compensation (NEEDS_ATTENTION → CANCELLATION_PENDING) (ADMIN)")
    @PostMapping("/{bookingId}/retry-compensation")
    public BookingResponse retryCompensation(@PathVariable UUID bookingId, Authentication authentication) {
        AuthenticatedUser admin = AuthenticatedUser.from(authentication);
        cancellations.retryCompensation(bookingId, admin);
        return queries.view(bookingId, admin);
    }
}
