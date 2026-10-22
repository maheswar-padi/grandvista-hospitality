package com.grandvista.room.repository;

import com.grandvista.room.domain.InventoryEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface InventoryEventRepository extends JpaRepository<InventoryEvent, Long>, JpaSpecificationExecutor<InventoryEvent> {
}
