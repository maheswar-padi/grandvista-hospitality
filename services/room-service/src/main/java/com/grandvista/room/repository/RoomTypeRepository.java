package com.grandvista.room.repository;

import com.grandvista.room.domain.RoomType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomTypeRepository extends JpaRepository<RoomType, UUID> {

    List<RoomType> findAllByOrderBySortOrderAscNameAsc();

    Optional<RoomType> findByCode(String code);

    boolean existsByCode(String code);
}
