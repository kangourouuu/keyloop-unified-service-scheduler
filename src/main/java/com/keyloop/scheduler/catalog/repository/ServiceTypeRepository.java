package com.keyloop.scheduler.catalog.repository;

import com.keyloop.scheduler.catalog.entity.ServiceTypeEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceTypeRepository extends JpaRepository<ServiceTypeEntity, String> {

  Optional<ServiceTypeEntity> findByCodeAndActiveTrue(String code);

  List<ServiceTypeEntity> findByActiveTrueOrderByCode();
}
