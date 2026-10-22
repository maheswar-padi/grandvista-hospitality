package com.grandvista.booking.web;

import com.grandvista.booking.domain.CancellationPolicy;
import com.grandvista.booking.service.BookingOrchestrator;
import com.grandvista.booking.service.BookingQueryService;
import com.grandvista.booking.service.CancellationService;
import com.grandvista.booking.web.dto.BookingDtos.BookingResponse;
import com.grandvista.booking.web.dto.BookingDtos.BookingStatusResponse;
import com.grandvista.booking.web.dto.BookingDtos.CancelRequest;
import com.grandvista.booking.web.dto.BookingDtos.CancellationQuote;
import com.grandvista.booking.web.dto.BookingDtos.CreateBookingRequest;
import com.grandvista.booking.web.dto.BookingDtos.HistoryEntry;
import com.grandvista.platform.security.AuthenticatedUser;
import com.grandvista.platform.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bookings")
@Validated
@Tag(name = "Bookings")
@SecurityRequirement(name = "bearer-jwt")
public class BookingController {

    private final BookingOrchestrator orchestrator;
    private final BookingQueryService queries;
    private final CancellationService cancellations;

    public BookingController(BookingOrchestrator orchestrator, BookingQueryService queries,
            CancellationService cancellations) {
        this.orchestrator = orchestrator;
        this.queries = queries;
        this.cancellations = cancellations;
    }

    @Operation(summary = "Create a booking: holds the room and prepares payment (CUSTOMER). Retries with the same "
            + "Idempotency-Key return the same booking.")
    @PostMapping
    public ResponseEntity<BookingResponse> create(
            @Parameter(description = "Client-generated unique key, e.g. a UUID") @RequestHeader("Idempotency-Key")
            @Pattern(regexp = "^[A-Za-z0-9_-]{8,80}$") String idempotencyKey,
            @Valid @RequestBody CreateBookingRequest request, Authentication authentication) {
        AuthenticatedUser user = AuthenticatedUser.from(authentication);
        var result = orchestrator.create(request, idempotencyKey, user);
        BookingResponse body = queries.view(result.booking().getId(), user);
        return result.created()
                ? ResponseEntity.created(URI.create("/api/v1/bookings/" + body.id())).body(body)
                : ResponseEntity.ok(body);
    }

    @Operation(summary = "The caller's bookings")
    @GetMapping("/my")
    public PageResponse<BookingResponse> mine(Authentication authentication,
            @RequestParam(defaultValue = "ALL") BookingQueryService.Scope scope,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return queries.mine(AuthenticatedUser.from(authentication), scope, page, size);
    }

    @Operation(summary = "A booking (owner, STAFF or ADMIN)")
    @GetMapping("/{bookingId}")
    public BookingResponse get(@PathVariable UUID bookingId, Authentication authentication) {
        return queries.view(bookingId, AuthenticatedUser.from(authentication));
    }

    @Operation(summary = "Authoritative booking status (for polling after payment)")
    @GetMapping("/{bookingId}/status")
    @Transactional(readOnly = true)
    public BookingStatusResponse status(@PathVariable UUID bookingId, Authentication authentication) {
        var b = queries.getForCaller(bookingId, AuthenticatedUser.from(authentication));
        return new BookingStatusResponse(b.getId(), b.getReference(), b.getStatus(), b.getStatusReason(), b.getPaymentId(),
                b.getHoldExpiresAt(), b.getUpdatedAt());
    }

    @Operation(summary = "Status history (audit trail) of a booking")
    @GetMapping("/{bookingId}/history")
    public List<HistoryEntry> history(@PathVariable UUID bookingId, Authentication authentication) {
        return queries.history(bookingId, AuthenticatedUser.from(authentication));
    }

    @Operation(summary = "Preview the refund the cancellation policy would give now")
    @GetMapping("/{bookingId}/cancellation-quote")
    @Transactional(readOnly = true)
    public CancellationQuote cancellationQuote(@PathVariable UUID bookingId, Authentication authentication) {
        AuthenticatedUser user = AuthenticatedUser.from(authentication);
        var booking = queries.getForCaller(bookingId, user);
        CancellationPolicy.Outcome o = cancellations.preview(booking, user);
        return new CancellationQuote(bookingId, o.allowed(), o.refundPercent(), o.refundAmount(), booking.getCurrency(),
                o.daysBeforeCheckIn(), o.rule());
    }

    @Operation(summary = "Cancel a booking; refund follows the cancellation policy (hotel-initiated: full refund)")
    @PostMapping("/{bookingId}/cancel")
    public ResponseEntity<BookingResponse> cancel(@PathVariable UUID bookingId,
            @Valid @RequestBody(required = false) CancelRequest request, Authentication authentication) {
        AuthenticatedUser user = AuthenticatedUser.from(authentication);
        queries.getForCaller(bookingId, user); // ownership check before any action
        var booking = cancellations.cancel(bookingId, user, request == null ? null : request.reason());
        BookingResponse body = queries.view(booking.getId(), user);
        return ResponseEntity.status(body.status().name().equals("CANCELLATION_PENDING") ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(body);
    }
}
