package com.keyloop.scheduler.catalog.repository;

import com.keyloop.scheduler.catalog.entity.VehicleEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VehicleRepository extends JpaRepository<VehicleEntity, UUID> {}
