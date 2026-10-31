package com.keyloop.scheduler.audit.repository;

import com.keyloop.scheduler.audit.entity.RequestAuditEntity;
import com.keyloop.scheduler.audit.entity.RequestAuditKey;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RequestAuditRepository extends JpaRepository<RequestAuditEntity, RequestAuditKey> {

  @Modifying
  @Query(
      value = "DELETE FROM request_audit_default WHERE occurred_at < :cutoff",
      nativeQuery = true)
  int deleteDefaultRowsBefore(@Param("cutoff") Instant cutoff);
}
