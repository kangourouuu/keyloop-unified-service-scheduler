package com.keyloop.scheduler.catalog;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TechnicianRepository extends JpaRepository<TechnicianEntity, UUID> {

  @Query(
      "select t.id from TechnicianEntity t join t.skillCodes s "
          + "where t.dealershipId = :dealershipId and t.active = true and s = :serviceTypeCode")
  List<UUID> findQualifiedActiveIds(
      @Param("dealershipId") UUID dealershipId, @Param("serviceTypeCode") String serviceTypeCode);

  List<TechnicianEntity> findByDealershipIdOrderByFullName(UUID dealershipId);
}
