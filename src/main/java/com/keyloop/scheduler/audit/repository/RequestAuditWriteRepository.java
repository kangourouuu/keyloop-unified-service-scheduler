package com.keyloop.scheduler.audit.repository;

import com.keyloop.scheduler.audit.entity.RequestAuditEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

@Repository
public class RequestAuditWriteRepository {

  @PersistenceContext private EntityManager entityManager;

  public void insert(RequestAuditEntity audit) {
    entityManager.persist(audit);
    entityManager.flush();
  }
}
