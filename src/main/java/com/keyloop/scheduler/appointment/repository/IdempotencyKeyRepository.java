package com.keyloop.scheduler.appointment.repository;

import com.keyloop.scheduler.appointment.entity.IdempotencyKeyEntity;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKeyEntity, String> {

  @Modifying
  @Query("delete from IdempotencyKeyEntity e where e.createdAt < :cutoff")
  int deleteCreatedBefore(@Param("cutoff") Instant cutoff);

  @Modifying
  @Query(
      """
      update IdempotencyKeyEntity e
      set e.responseStatus = :status, e.responseBody = :body
      where e.key = :key and e.responseStatus is null
      """)
  int complete(@Param("key") String key, @Param("status") int status, @Param("body") String body);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("delete from IdempotencyKeyEntity e where e.key = :key and e.createdAt < :cutoff")
  int deleteExpired(@Param("key") String key, @Param("cutoff") Instant cutoff);
}
