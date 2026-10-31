package com.keyloop.scheduler.catalog.repository;

import com.keyloop.scheduler.catalog.entity.DealershipEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DealershipRepository extends JpaRepository<DealershipEntity, UUID> {}
