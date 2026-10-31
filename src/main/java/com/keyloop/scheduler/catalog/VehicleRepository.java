package com.keyloop.scheduler.catalog;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface VehicleRepository extends JpaRepository<VehicleEntity, UUID> {}
