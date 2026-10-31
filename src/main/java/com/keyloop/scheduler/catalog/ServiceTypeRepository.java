package com.keyloop.scheduler.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

interface ServiceTypeRepository extends JpaRepository<ServiceTypeEntity, String> {}
