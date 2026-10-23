package com.grandvista.room.repository;

import com.grandvista.room.domain.RoomType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomTypeRepository extends JpaRepository<RoomType, UUID> {

    List<RoomType> findAllByOrderBySortOrderAscNameAsc();

    List<RoomType> findAllByPropertyIdOrderBySortOrderAscNameAsc(UUID propertyId);

    Optional<RoomType> findByPropertyIdAndCode(UUID propertyId, String code);

    boolean existsByPropertyIdAndCode(UUID propertyId, String code);
}
