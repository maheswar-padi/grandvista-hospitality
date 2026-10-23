package com.grandvista.room.web;

import com.grandvista.platform.error.ApiException;
import com.grandvista.room.domain.Property;
import com.grandvista.room.domain.ReconciliationRun;
import com.grandvista.room.reconciliation.InventoryReconciliationService;
import com.grandvista.room.service.InventoryAuditService;
import com.grandvista.room.service.PropertyService;
import com.grandvista.room.web.dto.ReconciliationDtos.ReconciliationReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/inventory/reconciliation")
@Validated
@Tag(name = "Inventory reconciliation")
@SecurityRequirement(name = "bearer-jwt")
public class AdminReconciliationController {

    private final InventoryReconciliationService reconciliation;
    private final PropertyService properties;

    public AdminReconciliationController(InventoryReconciliationService reconciliation, PropertyService properties) {
        this.reconciliation = reconciliation;
        this.properties = properties;
    }

    @Operation(summary = "Latest reconciliation report of a property, with findings (STAFF, ADMIN)")
    @GetMapping
    public ReconciliationReport latest(@RequestParam(required = false) UUID propertyId) {
        Property property = properties.resolve(propertyId);
        ReconciliationRun run = reconciliation.latest(property.getId()).orElseThrow(() -> ApiException.notFound(
                "RECONCILIATION_NOT_RUN", "No reconciliation has run for this property yet."));
        return report(run, property, true);
    }

    @Operation(summary = "Recent reconciliation runs of a property, newest first, without findings (STAFF, ADMIN)")
    @GetMapping("/runs")
    public List<ReconciliationReport> runs(@RequestParam(required = false) UUID propertyId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        Property property = properties.resolve(propertyId);
        return reconciliation.history(property.getId(), limit).stream().map(r -> report(r, property, false)).toList();
    }

    @Operation(summary = "Run a reconciliation for a property now and return its report (STAFF, ADMIN)")
    @PostMapping("/run")
    public ReconciliationReport runNow(@RequestParam(required = false) UUID propertyId) {
        Property property = properties.resolve(propertyId);
        ReconciliationRun run = reconciliation.runNow(property.getId(), InventoryAuditService.currentActor());
        return report(run, property, true);
    }

    private ReconciliationReport report(ReconciliationRun run, Property property, boolean withFindings) {
        return ReconciliationReport.of(run, property.getCode(), property.getName(), reconciliation.findings(run), withFindings);
    }
}
