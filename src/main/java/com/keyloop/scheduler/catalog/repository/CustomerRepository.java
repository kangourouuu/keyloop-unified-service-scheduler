package com.keyloop.scheduler.catalog.repository;

import com.keyloop.scheduler.catalog.entity.CustomerEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<CustomerEntity, UUID> {}
