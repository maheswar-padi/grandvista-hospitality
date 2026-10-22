package com.grandvista.payment.web;

import com.grandvista.payment.domain.OutboxEvent;
import com.grandvista.payment.domain.Payment;
import com.grandvista.payment.domain.PaymentStatus;
import com.grandvista.payment.repository.OutboxEventRepository;
import com.grandvista.payment.repository.PaymentRepository;
import com.grandvista.payment.web.dto.PaymentDtos.PaymentResponse;
import com.grandvista.payment.web.dto.PaymentDtos.PaymentSummary;
import com.grandvista.payment.web.dto.PaymentDtos.StatusTotal;
import com.grandvista.platform.persistence.Specs;
import com.grandvista.platform.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only payment visibility for hotel personnel. Outcomes can only change through the provider. */
@RestController
@RequestMapping("/api/v1/admin/payments")
@Validated
@Tag(name = "Payment administration")
@SecurityRequirement(name = "bearer-jwt")
public class AdminPaymentController {

    private final PaymentRepository payments;
    private final OutboxEventRepository outbox;

    public AdminPaymentController(PaymentRepository payments, OutboxEventRepository outbox) {
        this.payments = payments;
        this.outbox = outbox;
    }

    @Operation(summary = "Search payments (STAFF, ADMIN)")
    @GetMapping
    @Transactional(readOnly = true)
    public PageResponse<PaymentResponse> search(@RequestParam(required = false) PaymentStatus status,
            @RequestParam(required = false) UUID bookingId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(200) int size) {
        Specification<Payment> filter = Specs.allOf(Specs.eq("status", status), Specs.eq("bookingId", bookingId));
        return PageResponse.of(payments.findAll(filter, PageRequest.of(page, size, Sort.by("createdAt").descending())),
                PaymentResponse::from);
    }

    @Operation(summary = "Totals by status and outbox health (STAFF, ADMIN)")
    @GetMapping("/summary")
    @Transactional(readOnly = true)
    public PaymentSummary summary() {
        var totals = payments.totalsByStatus().stream()
                .map(t -> new StatusTotal(t.getStatus(), t.getCount(), t.getAmount(), t.getRefunded())).toList();
        BigDecimal captured = totals.stream().filter(t -> t.status().isCaptured())
                .map(StatusTotal::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal refunded = totals.stream().map(StatusTotal::refunded).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new PaymentSummary(totals, captured, refunded, outbox.countByStatus(OutboxEvent.Status.PENDING),
                outbox.countByStatus(OutboxEvent.Status.DEAD));
    }
}
