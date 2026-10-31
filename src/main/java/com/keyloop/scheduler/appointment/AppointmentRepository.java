package com.keyloop.scheduler.appointment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppointmentRepository extends JpaRepository<AppointmentEntity, UUID> {

  @Query(
      """
      select a from AppointmentEntity a
      where a.dealershipId = :dealershipId
        and a.status = com.keyloop.scheduler.appointment.AppointmentStatus.CONFIRMED
        and a.startTime < :windowEnd
        and a.endTime > :windowStart
      """)
  List<AppointmentEntity> findConfirmedOverlapping(
      @Param("dealershipId") UUID dealershipId,
      @Param("windowStart") Instant windowStart,
      @Param("windowEnd") Instant windowEnd);
}
