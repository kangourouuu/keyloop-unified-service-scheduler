package com.keyloop.scheduler.catalog;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface TechnicianRepository extends JpaRepository<TechnicianEntity, UUID> {}
