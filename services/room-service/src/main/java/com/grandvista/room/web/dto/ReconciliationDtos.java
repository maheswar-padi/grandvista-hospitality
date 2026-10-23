package com.grandvista.room.web.dto;

import com.grandvista.room.domain.ReconciliationRun;
import com.grandvista.room.reconciliation.ReconciliationEngine.Finding;
import com.grandvista.room.reconciliation.ReconciliationEngine.FindingType;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ReconciliationDtos {

    private ReconciliationDtos() {
    }

    /** One run with its findings ({@code findings} is empty in the history list). */
    public record ReconciliationReport(UUID id, UUID propertyId, String propertyCode, String propertyName,
                                       ReconciliationRun.Trigger trigger, ReconciliationRun.Status status,
                                       Instant startedAt, Instant finishedAt, String actor, int allocationsChecked,
                                       int bookingsChecked, int findingCount, int releasedCount,
                                       Map<FindingType, Long> countsByType, String error, List<Finding> findings) {

        public static ReconciliationReport of(ReconciliationRun run, String code, String name, List<Finding> findings,
                boolean includeFindings) {
            Map<FindingType, Long> counts = new EnumMap<>(FindingType.class);
            for (FindingType t : FindingType.values()) {
                counts.put(t, 0L);
            }
            findings.forEach(f -> counts.merge(f.type(), 1L, Long::sum));
            return new ReconciliationReport(run.getId(), run.getPropertyId(), code, name, run.getTrigger(), run.getStatus(),
                    run.getStartedAt(), run.getFinishedAt(), run.getActor(), run.getAllocationsChecked(),
                    run.getBookingsChecked(), run.getFindingCount(), run.getReleasedCount(), counts, run.getError(),
                    includeFindings ? findings : List.of());
        }
    }
}
