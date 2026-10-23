package com.grandvista.room.reconciliation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grandvista.platform.error.ApiException;
import com.grandvista.room.domain.AllocationStatus;
import com.grandvista.room.domain.InventoryAllocation;
import com.grandvista.room.domain.InventoryEvent;
import com.grandvista.room.domain.Property;
import com.grandvista.room.domain.ReconciliationRun;
import com.grandvista.room.reconciliation.ReconciliationEngine.Action;
import com.grandvista.room.reconciliation.ReconciliationEngine.AllocationFact;
import com.grandvista.room.reconciliation.ReconciliationEngine.BookingFact;
import com.grandvista.room.reconciliation.ReconciliationEngine.Finding;
import com.grandvista.room.repository.InventoryAllocationRepository;
import com.grandvista.room.repository.ReconciliationRunRepository;
import com.grandvista.room.repository.RoomRepository;
import com.grandvista.room.service.InventoryAuditService;
import com.grandvista.room.service.PropertyService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

/**
 * Stock reconciliation per property: room-service allocations vs booking-service bookings.
 * <ul>
 *   <li>one run at a time per property, across all instances (PostgreSQL advisory lock);</li>
 *   <li>expired orphan holds are released; every other finding is reported for a person to resolve;</li>
 *   <li>the audit trail gets RECONCILIATION_RUN for every run, RECONCILIATION_FINDING the first time a finding
 *       appears and RECONCILIATION_RELEASED for every release.</li>
 * </ul>
 */
@Service
public class InventoryReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(InventoryReconciliationService.class);
    static final String RELEASE_REASON = "RECONCILIATION_ORPHAN_HOLD";
    private static final TypeReference<List<Finding>> FINDINGS = new TypeReference<>() {
    };

    private final PropertyService properties;
    private final RoomRepository rooms;
    private final InventoryAllocationRepository allocations;
    private final ReconciliationRunRepository runs;
    private final BookingLookupClient bookings;
    private final InventoryAuditService audit;
    private final ReconciliationProperties config;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate tx;

    public InventoryReconciliationService(PropertyService properties, RoomRepository rooms,
            InventoryAllocationRepository allocations, ReconciliationRunRepository runs, BookingLookupClient bookings,
            InventoryAuditService audit, ReconciliationProperties config, ObjectMapper json, Clock clock,
            PlatformTransactionManager transactions) {
        this.properties = properties;
        this.rooms = rooms;
        this.allocations = allocations;
        this.runs = runs;
        this.bookings = bookings;
        this.audit = audit;
        this.config = config;
        this.json = json;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
    }

    /**
     * Runs a reconciliation for one property (null = default property).
     *
     * @return the stored run, or empty if another instance is reconciling this property right now
     */
    public java.util.Optional<ReconciliationRun> run(UUID propertyId, ReconciliationRun.Trigger trigger, String actor) {
        Property property = properties.resolve(propertyId);
        return java.util.Optional.ofNullable(tx.execute(status -> {
            if (!runs.tryLock(property.getId())) {
                return null;
            }
            return reconcile(property, trigger, actor);
        }));
    }

    /** Manual run from the admin API: 409 if a run for the property is already in progress. */
    public ReconciliationRun runNow(UUID propertyId, String actor) {
        return run(propertyId, ReconciliationRun.Trigger.MANUAL, actor).orElseThrow(() -> new ApiException(
                HttpStatus.CONFLICT, "RECONCILIATION_RUNNING", "A reconciliation for this property is already running"));
    }

    private ReconciliationRun reconcile(Property property, ReconciliationRun.Trigger trigger, String actor) {
        Instant started = clock.instant();
        LocalDate today = LocalDate.ofInstant(started, property.zoneId());
        Map<UUID, String> roomNumbers = rooms.findRoomNumbersByPropertyId(property.getId()).stream()
                .collect(Collectors.toMap(RoomRepository.RoomNumber::getId, RoomRepository.RoomNumber::getRoomNumber));

        List<InventoryAllocation> active = roomNumbers.isEmpty() ? List.of()
                : allocations.findActiveForRooms(roomNumbers.keySet(), today);
        List<BookingFact> holding;
        Map<UUID, BookingFact> byId;
        try {
            byId = bookings.lookup(active.stream().map(InventoryAllocation::getBookingId).toList()).stream()
                    .collect(Collectors.toMap(BookingFact::bookingId, Function.identity(), (a, b) -> a));
            holding = roomNumbers.isEmpty() ? List.of() : bookings.holdingInventory(roomNumbers.keySet(), today);
        } catch (RestClientException ex) {
            log.warn("Reconciliation of {} failed: booking-service lookup failed ({})", property.getCode(), ex.getMessage());
            ReconciliationRun failed = runs.save(ReconciliationRun.failed(property.getId(), trigger, actor, started,
                    clock.instant(), "booking-service unavailable: " + ex.getClass().getSimpleName()));
            audit.record(InventoryEvent.Type.RECONCILIATION_RUN, null, null, null,
                    "property=" + property.getCode() + " status=FAILED trigger=" + trigger);
            return failed;
        }

        Set<UUID> holdingIds = holding.stream().map(BookingFact::bookingId).collect(Collectors.toSet());
        Map<UUID, AllocationFact> allocationByBooking = (holdingIds.isEmpty() ? List.<InventoryAllocation>of()
                : allocations.findAllByBookingIdIn(holdingIds)).stream()
                .collect(Collectors.toMap(InventoryAllocation::getBookingId, a -> fact(a, roomNumbers)));

        List<Finding> findings = ReconciliationEngine.evaluate(active.stream().map(a -> fact(a, roomNumbers)).toList(),
                byId, holding, allocationByBooking, started, config.grace());

        Set<String> alreadyLogged = previousFindingKeys(property.getId());
        int released = 0;
        for (Finding f : findings) {
            if (!alreadyLogged.contains(f.key())) {
                audit.record(InventoryEvent.Type.RECONCILIATION_FINDING, f.roomId(), f.allocationId(), f.bookingId(),
                        f.type() + " (" + f.severity() + "): " + f.detail());
            }
            if (f.action() == Action.RELEASE_EXPIRED_ORPHAN_HOLD && release(f)) {
                released++;
            }
        }
        ReconciliationRun run = runs.save(ReconciliationRun.completed(property.getId(), trigger, actor, started,
                clock.instant(), active.size(), byId.size() + holding.size(), findings.size(), released, write(findings)));
        audit.record(InventoryEvent.Type.RECONCILIATION_RUN, null, null, null, "property=" + property.getCode()
                + " status=COMPLETED trigger=" + trigger + " findings=" + findings.size() + " released=" + released);
        if (!findings.isEmpty()) {
            log.info("Reconciliation of {}: {} finding(s), {} orphan hold(s) released", property.getCode(),
                    findings.size(), released);
        }
        return run;
    }

    /** Releases the hold only if it is still HELD and expired (the row is locked and re-checked). */
    private boolean release(Finding f) {
        Instant now = clock.instant();
        return allocations.findByBookingIdForUpdate(f.bookingId())
                .filter(a -> a.getStatus() == AllocationStatus.HELD && a.isHoldExpired(now))
                .map(a -> {
                    a.release(RELEASE_REASON, now);
                    audit.record(InventoryEvent.Type.RECONCILIATION_RELEASED, a.getRoomId(), a.getId(), a.getBookingId(),
                            "expired orphan hold released (" + f.detail() + ")");
                    return true;
                }).orElse(false);
    }

    private Set<String> previousFindingKeys(UUID propertyId) {
        return runs.findFirstByPropertyIdAndStatusOrderByStartedAtDesc(propertyId, ReconciliationRun.Status.COMPLETED)
                .map(r -> read(r.getFindings()).stream().map(Finding::key).collect(Collectors.toSet()))
                .orElseGet(HashSet::new);
    }

    @Transactional(readOnly = true)
    public java.util.Optional<ReconciliationRun> latest(UUID propertyId) {
        return runs.findFirstByPropertyIdOrderByStartedAtDesc(properties.resolve(propertyId).getId());
    }

    @Transactional(readOnly = true)
    public List<ReconciliationRun> history(UUID propertyId, int limit) {
        return runs.findByPropertyIdOrderByStartedAtDesc(properties.resolve(propertyId).getId(), PageRequest.of(0, limit));
    }

    public List<Finding> findings(ReconciliationRun run) {
        return read(run.getFindings());
    }

    private static AllocationFact fact(InventoryAllocation a, Map<UUID, String> roomNumbers) {
        return new AllocationFact(a.getId(), a.getBookingId(), a.getRoomId(), roomNumbers.get(a.getRoomId()), a.getCheckIn(),
                a.getCheckOut(), a.getStatus(), a.getHoldExpiresAt(), a.getUpdatedAt());
    }

    private String write(List<Finding> findings) {
        try {
            return json.writeValueAsString(findings);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private List<Finding> read(String findings) {
        try {
            return findings == null ? List.of() : json.readValue(findings, FINDINGS);
        } catch (JsonProcessingException ex) {
            log.warn("Unreadable reconciliation findings ignored: {}", ex.getOriginalMessage());
            return List.of();
        }
    }
}
