package com.keyloop.scheduler.appointment.repository;

import com.keyloop.scheduler.appointment.AppointmentEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

@Repository
public class AppointmentWriteRepository {

  @PersistenceContext private EntityManager entityManager;

  public void insert(AppointmentEntity appointment) {
    entityManager.persist(appointment);
    entityManager.flush();
  }
}
