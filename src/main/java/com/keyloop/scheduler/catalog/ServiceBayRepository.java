package com.keyloop.scheduler.catalog;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ServiceBayRepository extends JpaRepository<ServiceBayEntity, UUID> {}
