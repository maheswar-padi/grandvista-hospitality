package com.grandvista.room.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.grandvista.room.domain.AllocationStatus;
import com.grandvista.room.reconciliation.ReconciliationEngine.Action;
import com.grandvista.room.reconciliation.ReconciliationEngine.AllocationFact;
import com.grandvista.room.reconciliation.ReconciliationEngine.BookingFact;
import com.grandvista.room.reconciliation.ReconciliationEngine.Finding;
import com.grandvista.room.reconciliation.ReconciliationEngine.FindingType;
import com.grandvista.room.reconciliation.ReconciliationEngine.Severity;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReconciliationEngineTest {

    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");
    private static final Instant OLD = NOW.minus(Duration.ofHours(1));
    private static final Duration GRACE = Duration.ofMinutes(2);
    private static final LocalDate IN = LocalDate.parse("2026-11-10");
    private final UUID room = UUID.randomUUID();

    private AllocationFact allocation(AllocationStatus status, Instant holdExpiresAt, Instant updatedAt) {
        return new AllocationFact(UUID.randomUUID(), UUID.randomUUID(), room, "101", IN, IN.plusDays(2), status,
                holdExpiresAt, updatedAt);
    }

    private BookingFact booking(UUID id, String status, Instant holdExpiresAt) {
        return new BookingFact(id, "GV-" + id.toString().substring(0, 6), status, room, IN, IN.plusDays(2), holdExpiresAt, OLD);
    }

    private List<Finding> evaluate(List<AllocationFact> allocations, List<BookingFact> known, List<BookingFact> holding,
            Map<UUID, AllocationFact> byBooking) {
        Map<UUID, BookingFact> byId = new java.util.HashMap<>();
        known.forEach(b -> byId.put(b.bookingId(), b));
        return ReconciliationEngine.evaluate(allocations, byId, holding, byBooking, NOW, GRACE);
    }

    @Test
    void consistentAllocationsAndBookingsProduceNoFindings() {
        AllocationFact held = allocation(AllocationStatus.HELD, NOW.plusSeconds(300), OLD);
        AllocationFact confirmed = allocation(AllocationStatus.CONFIRMED, null, OLD);
        BookingFact pending = booking(held.bookingId(), "PENDING_PAYMENT", NOW.plusSeconds(300));
        BookingFact paid = booking(confirmed.bookingId(), "CONFIRMED", null);

        assertThat(evaluate(List.of(held, confirmed), List.of(pending, paid), List.of(pending, paid),
                Map.of(held.bookingId(), held, confirmed.bookingId(), confirmed))).isEmpty();
    }

    @Test
    void expiredHoldForUnknownBookingIsAnOrphanToRelease() {
        AllocationFact expired = allocation(AllocationStatus.HELD, NOW.minusSeconds(60), OLD);
        AllocationFact live = allocation(AllocationStatus.HELD, NOW.plusSeconds(600), OLD);

        List<Finding> findings = evaluate(List.of(expired, live), List.of(), List.of(), Map.of());

        assertThat(findings).extracting(Finding::type).containsOnly(FindingType.ORPHAN_HOLD);
        assertThat(findings).filteredOn(f -> f.allocationId().equals(expired.allocationId()))
                .extracting(Finding::action).containsExactly(Action.RELEASE_EXPIRED_ORPHAN_HOLD);
        assertThat(findings).filteredOn(f -> f.allocationId().equals(live.allocationId()))
                .extracting(Finding::action).containsExactly(Action.NONE);
    }

    @Test
    void holdOfAFinishedBookingIsAnOrphan() {
        AllocationFact held = allocation(AllocationStatus.HELD, NOW.minusSeconds(1), OLD);
        List<Finding> findings = evaluate(List.of(held), List.of(booking(held.bookingId(), "CANCELLED", null)), List.of(),
                Map.of());
        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.type()).isEqualTo(FindingType.ORPHAN_HOLD);
            assertThat(f.bookingStatus()).isEqualTo("CANCELLED");
            assertThat(f.action()).isEqualTo(Action.RELEASE_EXPIRED_ORPHAN_HOLD);
        });
    }

    @Test
    void confirmedAllocationWithoutBookingIsCriticalAndNeverAutoReleased() {
        AllocationFact confirmed = allocation(AllocationStatus.CONFIRMED, null, OLD);
        assertThat(evaluate(List.of(confirmed), List.of(), List.of(), Map.of())).singleElement().satisfies(f -> {
            assertThat(f.type()).isEqualTo(FindingType.ALLOCATION_WITHOUT_BOOKING);
            assertThat(f.severity()).isEqualTo(Severity.CRITICAL);
            assertThat(f.action()).isEqualTo(Action.NONE);
        });
    }

    @Test
    void statusMismatchesAreReportedBothWays() {
        AllocationFact confirmedButCancelled = allocation(AllocationStatus.CONFIRMED, null, OLD);
        AllocationFact heldButPaid = allocation(AllocationStatus.HELD, NOW.plusSeconds(60), OLD);
        BookingFact cancelled = booking(confirmedButCancelled.bookingId(), "CANCELLED", null);
        BookingFact paid = booking(heldButPaid.bookingId(), "CONFIRMED", null);

        List<Finding> findings = evaluate(List.of(confirmedButCancelled, heldButPaid), List.of(cancelled, paid),
                List.of(paid), Map.of(paid.bookingId(), heldButPaid));

        assertThat(findings).extracting(Finding::type).containsExactly(FindingType.STATUS_MISMATCH, FindingType.STATUS_MISMATCH);
    }

    @Test
    void bookingsWithoutAllocationsAreReported() {
        BookingFact confirmed = booking(UUID.randomUUID(), "CONFIRMED", null);
        BookingFact pending = booking(UUID.randomUUID(), "PENDING_PAYMENT", NOW.plusSeconds(300));
        BookingFact pendingLapsed = booking(UUID.randomUUID(), "PENDING_PAYMENT", NOW.minusSeconds(5));
        AllocationFact released = new AllocationFact(UUID.randomUUID(), confirmed.bookingId(), room, "101", IN, IN.plusDays(2),
                AllocationStatus.RELEASED, null, OLD);

        List<Finding> findings = evaluate(List.of(), List.of(), List.of(confirmed, pending, pendingLapsed),
                Map.of(confirmed.bookingId(), released));

        assertThat(findings).extracting(Finding::type).containsExactly(FindingType.CONFIRMED_BOOKING_WITHOUT_ALLOCATION,
                FindingType.PENDING_BOOKING_WITHOUT_HOLD);
        assertThat(findings.get(0).detail()).contains("RELEASED");
        assertThat(findings.get(0).severity()).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void recordsChangedWithinTheGracePeriodAreSkipped() {
        AllocationFact fresh = allocation(AllocationStatus.HELD, NOW.minusSeconds(1), NOW.minusSeconds(30));
        BookingFact freshBooking = new BookingFact(UUID.randomUUID(), "GV-NEW", "CONFIRMED", room, IN, IN.plusDays(1), null,
                NOW.minusSeconds(10));
        assertThat(evaluate(List.of(fresh), List.of(), List.of(freshBooking), Map.of())).isEmpty();
    }

    @Test
    void findingKeysIdentifyTheSameProblemAcrossRuns() {
        AllocationFact orphan = allocation(AllocationStatus.HELD, NOW.plusSeconds(600), OLD);
        Finding first = evaluate(List.of(orphan), List.of(), List.of(), Map.of()).get(0);
        Finding second = ReconciliationEngine.evaluate(List.of(orphan), Map.of(), List.of(), Map.of(),
                NOW.plusSeconds(60), GRACE).get(0);
        assertThat(first.key()).isEqualTo(second.key()).startsWith("ORPHAN_HOLD:");
    }

    @Test
    void bookingIdsAreSentInChunksOfAThousand() {
        List<UUID> ids = java.util.stream.Stream.generate(UUID::randomUUID).limit(2_345).toList();
        assertThat(BookingLookupClient.chunks(ids)).extracting(List::size).containsExactly(1000, 1000, 345);
        assertThat(BookingLookupClient.chunks(List.of())).isEmpty();
    }
}
