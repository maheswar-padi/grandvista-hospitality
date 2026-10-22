package com.grandvista.room.repository;

import com.grandvista.room.domain.Amenity;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AmenityRepository extends JpaRepository<Amenity, UUID> {

    List<Amenity> findAllByOrderByCategoryAscNameAsc();

    List<Amenity> findByCodeIn(Collection<String> codes);

    boolean existsByCode(String code);
}
