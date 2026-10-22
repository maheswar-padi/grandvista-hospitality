package com.grandvista.room.domain;

/** Operational status. Only ACTIVE rooms can be searched, quoted for sale or held. */
public enum RoomStatus {
    ACTIVE,
    MAINTENANCE,
    OUT_OF_SERVICE,
    /** Retired from inventory (soft delete). History and allocations are preserved. */
    INACTIVE;

    public boolean isSellable() {
        return this == ACTIVE;
    }
}
