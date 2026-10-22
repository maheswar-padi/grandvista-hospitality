package com.grandvista.payment.web;

import com.grandvista.payment.domain.Payment;
import com.grandvista.payment.service.PaymentService;
import com.grandvista.payment.service.RefundService;
import com.grandvista.payment.web.dto.PaymentDtos.CancelPaymentRequest;
import com.grandvista.payment.web.dto.PaymentDtos.CreatePaymentRequest;
import com.grandvista.payment.web.dto.PaymentDtos.PaymentResponse;
import com.grandvista.payment.web.dto.PaymentDtos.RefundRequest;
import com.grandvista.payment.web.dto.PaymentDtos.RefundResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service payment API for the booking saga (SERVICE token required; not routed publicly). */
@RestController
@RequestMapping("/internal/v1/payments")
@Tag(name = "Internal: payments")
public class InternalPaymentController {

    private final PaymentService payments;
    private final RefundService refunds;

    public InternalPaymentController(PaymentService payments, RefundService refunds) {
        this.payments = payments;
        this.refunds = refunds;
    }

    @Operation(summary = "Create the payment for a booking (idempotent per bookingId)")
    @PostMapping
    public ResponseEntity<PaymentResponse> create(@Valid @RequestBody CreatePaymentRequest request) {
        Payment payment = payments.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(PaymentResponse.from(payment));
    }

    @Operation(summary = "Payment of a booking")
    @GetMapping("/by-booking/{bookingId}")
    public PaymentResponse byBooking(@PathVariable UUID bookingId) {
        return PaymentResponse.from(payments.getByBooking(bookingId));
    }

    @Operation(summary = "Void an unpaid payment (idempotent); 409 with the current status if already in progress/captured")
    @PostMapping("/{paymentId}/cancel")
    public PaymentResponse cancel(@PathVariable UUID paymentId, @Valid @RequestBody CancelPaymentRequest request) {
        return PaymentResponse.from(payments.cancel(paymentId, request.reason()));
    }

    @Operation(summary = "Refund part or all of a captured payment (idempotent per idempotencyKey)")
    @PostMapping("/{paymentId}/refunds")
    public RefundResponse refund(@PathVariable UUID paymentId, @Valid @RequestBody RefundRequest request) {
        return RefundResponse.from(refunds.refund(paymentId, request));
    }
}
