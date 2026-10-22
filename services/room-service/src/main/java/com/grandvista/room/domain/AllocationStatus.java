package com.grandvista.room.domain;

/**
 * Lifecycle of an inventory allocation:
 * <pre>
 *   HELD ──confirm──▶ CONFIRMED ──release──▶ RELEASED
 *    │  └─release──▶ RELEASED
 *    └──expiry────▶ EXPIRED ──confirm (re-acquire if still free)──▶ CONFIRMED
 * </pre>
 * Only HELD and CONFIRMED allocations block the room (enforced by the exclusion constraint).
 */
public enum AllocationStatus {
    HELD,
    CONFIRMED,
    RELEASED,
    EXPIRED;

    public boolean blocksInventory() {
        return this == HELD || this == CONFIRMED;
    }
}
