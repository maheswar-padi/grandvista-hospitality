package com.grandvista.room.web;

import com.grandvista.room.service.InventoryService;
import com.grandvista.room.web.dto.InventoryDtos.HoldRequest;
import com.grandvista.room.web.dto.InventoryDtos.HoldResponse;
import com.grandvista.room.web.dto.InventoryDtos.ReleaseRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service inventory API for the booking saga. Requires a SERVICE token and is never routed by
 * the public gateway. Every operation is idempotent per bookingId so callers can retry safely.
 */
@RestController
@RequestMapping("/internal/v1/holds")
@Tag(name = "Internal: inventory holds")
public class InternalInventoryController {

    private final InventoryService inventory;

    public InternalInventoryController(InventoryService inventory) {
        this.inventory = inventory;
    }

    @Operation(summary = "Place a temporary hold and get the authoritative price")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HoldResponse hold(@Valid @RequestBody HoldRequest request) {
        return inventory.hold(request);
    }

    @Operation(summary = "Current state of a booking's hold")
    @GetMapping("/{bookingId}")
    public HoldResponse get(@PathVariable UUID bookingId) {
        return inventory.get(bookingId);
    }

    @Operation(summary = "Confirm the hold after successful payment")
    @PostMapping("/{bookingId}/confirm")
    public HoldResponse confirm(@PathVariable UUID bookingId) {
        return inventory.confirm(bookingId);
    }

    @Operation(summary = "Release the hold or allocation (failed payment, expiry, cancellation)")
    @PostMapping("/{bookingId}/release")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@PathVariable UUID bookingId, @Valid @RequestBody ReleaseRequest request) {
        inventory.release(bookingId, request.reason());
    }
}
