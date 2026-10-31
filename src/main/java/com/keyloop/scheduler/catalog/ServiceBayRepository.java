package com.keyloop.scheduler.catalog;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ServiceBayRepository extends JpaRepository<ServiceBayEntity, UUID> {

  @Query(
      "select b.id from ServiceBayEntity b where b.dealershipId = :dealershipId and b.active = true")
  List<UUID> findActiveIds(@Param("dealershipId") UUID dealershipId);

  List<ServiceBayEntity> findByDealershipIdOrderByBayCode(UUID dealershipId);
}
