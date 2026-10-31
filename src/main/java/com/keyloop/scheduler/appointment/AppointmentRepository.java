package com.keyloop.scheduler.appointment;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AppointmentRepository extends JpaRepository<AppointmentEntity, UUID> {}
