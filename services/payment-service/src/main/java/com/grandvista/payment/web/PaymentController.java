package com.grandvista.payment.web;

import com.grandvista.payment.repository.PaymentEventRepository;
import com.grandvista.payment.repository.RefundRepository;
import com.grandvista.payment.service.PaymentService;
import com.grandvista.payment.web.dto.PaymentDtos.ConfirmPaymentRequest;
import com.grandvista.payment.web.dto.PaymentDtos.PaymentDetailResponse;
import com.grandvista.payment.web.dto.PaymentDtos.PaymentEventResponse;
import com.grandvista.payment.web.dto.PaymentDtos.PaymentResponse;
import com.grandvista.payment.web.dto.PaymentDtos.RefundResponse;
import com.grandvista.payment.web.dto.PaymentDtos.TestPaymentMethod;
import com.grandvista.platform.security.AuthenticatedUser;
import com.grandvista.platform.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
@RequestMapping("/api/v1/payments")
@Validated
@Tag(name = "Payments")
@SecurityRequirement(name = "bearer-jwt")
public class PaymentController {

    private final PaymentService payments;
    private final RefundRepository refunds;
    private final PaymentEventRepository events;

    public PaymentController(PaymentService payments, RefundRepository refunds, PaymentEventRepository events) {
        this.payments = payments;
        this.refunds = refunds;
        this.events = events;
    }

    @Operation(summary = "Test payment methods accepted by the SIMULATED provider (no real money is charged)")
    @GetMapping("/test-methods")
    public List<TestPaymentMethod> testMethods() {
        return List.of(
                new TestPaymentMethod("sim_card_success", "Simulated Visa – approved", "SUCCEEDED"),
                new TestPaymentMethod("sim_card_success_mastercard", "Simulated Mastercard – approved", "SUCCEEDED"),
                new TestPaymentMethod("sim_card_declined", "Simulated card – declined", "FAILED"),
                new TestPaymentMethod("sim_card_insufficient_funds", "Simulated card – insufficient funds", "FAILED"),
                new TestPaymentMethod("sim_card_async_success", "Simulated bank redirect – approved by webhook", "SUCCEEDED (async)"),
                new TestPaymentMethod("sim_card_async_decline", "Simulated bank redirect – declined by webhook", "FAILED (async)"),
                new TestPaymentMethod("sim_card_timeout_success", "Simulated timeout – resolved by reconciliation", "SUCCEEDED (delayed)"));
    }

    @Operation(summary = "Authorise the payment with a provider payment-method token. Requires an Idempotency-Key header.")
    @PostMapping("/{paymentId}/confirm")
    public PaymentResponse confirm(@PathVariable UUID paymentId,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "^[A-Za-z0-9_-]{8,80}$") String idempotencyKey,
            @Valid @RequestBody ConfirmPaymentRequest request, Authentication authentication) {
        return PaymentResponse.from(payments.confirm(paymentId, idempotencyKey, request.paymentMethodToken(),
                AuthenticatedUser.from(authentication)));
    }

    @Operation(summary = "A payment (owner, STAFF or ADMIN)")
    @GetMapping("/{paymentId}")
    @Transactional(readOnly = true)
    public PaymentDetailResponse get(@PathVariable UUID paymentId, Authentication authentication) {
        var payment = payments.getForCaller(paymentId, AuthenticatedUser.from(authentication));
        return new PaymentDetailResponse(PaymentResponse.from(payment),
                refunds.findByPaymentIdOrderByCreatedAtAsc(paymentId).stream().map(RefundResponse::from).toList(),
                events.findByPaymentIdOrderByOccurredAtAscIdAsc(paymentId).stream().map(PaymentEventResponse::from).toList());
    }

    @Operation(summary = "The payment of a booking (owner, STAFF or ADMIN)")
    @GetMapping("/by-booking/{bookingId}")
    public PaymentResponse byBooking(@PathVariable UUID bookingId, Authentication authentication) {
        return PaymentResponse.from(payments.getByBookingForCaller(bookingId, AuthenticatedUser.from(authentication)));
    }

    @Operation(summary = "The caller's payment history")
    @GetMapping("/my")
    public PageResponse<PaymentResponse> mine(Authentication authentication,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        UUID customerId = AuthenticatedUser.from(authentication).requireUserId();
        return PageResponse.of(payments.history(customerId, PageRequest.of(page, size, Sort.by("createdAt").descending())),
                PaymentResponse::from);
    }
}
