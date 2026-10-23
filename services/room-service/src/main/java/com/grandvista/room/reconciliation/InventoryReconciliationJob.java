package com.grandvista.room.reconciliation;

import com.grandvista.room.domain.ReconciliationRun;
import com.grandvista.room.repository.PropertyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Reconciles every active property on a fixed delay; a failure for one property does not stop the others. */
@Component
@ConditionalOnProperty(name = "gv.inventory.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
public class InventoryReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(InventoryReconciliationJob.class);

    private final PropertyRepository properties;
    private final InventoryReconciliationService reconciliation;

    public InventoryReconciliationJob(PropertyRepository properties, InventoryReconciliationService reconciliation) {
        this.properties = properties;
        this.reconciliation = reconciliation;
    }

    @Scheduled(fixedDelayString = "${gv.inventory.reconciliation.interval:10m}",
            initialDelayString = "${gv.inventory.reconciliation.initial-delay:2m}")
    public void reconcileAll() {
        for (var property : properties.findByActiveTrueOrderByNameAsc()) {
            try {
                reconciliation.run(property.getId(), ReconciliationRun.Trigger.SCHEDULED, "system");
            } catch (RuntimeException ex) {
                log.error("Scheduled reconciliation of {} failed; it will be retried on the next run", property.getCode(), ex);
            }
        }
    }
}
