package com.grandvista.booking.web;

import com.grandvista.booking.service.PaymentEventService;
import com.grandvista.booking.web.dto.BookingDtos.PaymentEventMessage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Receives payment events from the payment-service outbox (SERVICE token; not routed by the gateway). */
@RestController
@RequestMapping("/internal/v1/payment-events")
@Tag(name = "Internal: payment events")
public class InternalPaymentEventController {

    private final PaymentEventService events;

    public InternalPaymentEventController(PaymentEventService events) {
        this.events = events;
    }

    @Operation(summary = "Apply a payment event (idempotent by eventId)")
    @PostMapping
    public Map<String, String> receive(@Valid @RequestBody PaymentEventMessage event) {
        return Map.of("result", events.handle(event).name());
    }
}
