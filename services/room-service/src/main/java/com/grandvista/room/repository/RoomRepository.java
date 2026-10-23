package com.grandvista.room.repository;

import com.grandvista.room.domain.Room;
import com.grandvista.room.domain.RoomStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface RoomRepository extends JpaRepository<Room, UUID>, JpaSpecificationExecutor<Room> {

    /** Loads a page of rooms with type, amenities and images in a bounded number of queries (no N+1). */
    @EntityGraph(attributePaths = {"roomType", "amenities", "property"})
    List<Room> findAllByIdIn(Collection<UUID> ids);

    @EntityGraph(attributePaths = {"roomType", "amenities", "property"})
    Optional<Room> findWithDetailsById(UUID id);

    /**
     * Exclusive row lock on the room. Taken while placing or confirming a hold and while changing status,
     * so concurrent holds on the <em>same</em> room are serialised (different rooms proceed in parallel).
     * Without it, two transactions inserting conflicting rows into the GiST exclusion constraint can each
     * wait for the other and be aborted as a deadlock; with it the loser waits and then fails cleanly.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Room r where r.id = :id")
    Optional<Room> findByIdForUpdate(UUID id);

    boolean existsByPropertyIdAndRoomNumber(UUID propertyId, String roomNumber);

    long countByPropertyId(UUID propertyId);

    long countByPropertyIdAndStatus(UUID propertyId, RoomStatus status);

    long countByRoomTypeIdAndStatus(UUID roomTypeId, RoomStatus status);

    long countByStatus(RoomStatus status);

}
