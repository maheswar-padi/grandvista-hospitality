package com.grandvista.room.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically returns rooms whose payment hold has lapsed to the available pool. */
@Component
public class HoldExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(HoldExpiryJob.class);
    private static final int BATCH = 200;

    private final InventoryService inventory;

    public HoldExpiryJob(InventoryService inventory) {
        this.inventory = inventory;
    }

    @Scheduled(fixedDelayString = "${gv.inventory.hold-sweep-interval:30s}", initialDelayString = "${gv.inventory.hold-sweep-initial-delay:20s}")
    public void sweep() {
        try {
            int expired;
            int total = 0;
            do {
                expired = inventory.expireDueHolds(BATCH);
                total += expired;
            } while (expired == BATCH);
            if (total > 0) {
                log.info("Expired {} lapsed inventory hold(s)", total);
            }
        } catch (RuntimeException ex) {
            log.error("Hold expiry sweep failed; it will be retried on the next run", ex);
        }
    }
}
