package com.grandvista.room.repository;

import com.grandvista.room.domain.Property;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PropertyRepository extends JpaRepository<Property, UUID> {

    List<Property> findAllByOrderByNameAsc();

    List<Property> findByActiveTrueOrderByNameAsc();

    Optional<Property> findByCode(String code);

    boolean existsByCode(String code);
}
