package com.keyloop.scheduler.appointment.repository;

import com.keyloop.scheduler.appointment.entity.AppointmentEntity;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppointmentRepository extends JpaRepository<AppointmentEntity, UUID> {

  @Query(
      """
      select a from AppointmentEntity a
      where a.dealershipId = :dealershipId
        and a.status = com.keyloop.scheduler.appointment.entity.AppointmentStatus.CONFIRMED
        and a.startTime < :windowEnd
        and a.endTime > :windowStart
      """)
  List<AppointmentEntity> findConfirmedOverlapping(
      @Param("dealershipId") UUID dealershipId,
      @Param("windowStart") Instant windowStart,
      @Param("windowEnd") Instant windowEnd);

  @Query(
      """
      select b.id from ServiceBayEntity b
      where b.dealershipId = :dealershipId
        and b.active = true
        and not exists (
          select a.id from AppointmentEntity a
          where a.serviceBayId = b.id
            and a.status = com.keyloop.scheduler.appointment.entity.AppointmentStatus.CONFIRMED
            and a.startTime < :slotEnd
            and a.endTime > :slotStart)
      """)
  List<UUID> findFreeBayIds(
      @Param("dealershipId") UUID dealershipId,
      @Param("slotStart") Instant slotStart,
      @Param("slotEnd") Instant slotEnd);

  @Query(
      """
      select t.id from TechnicianEntity t join t.skillCodes s
      where t.dealershipId = :dealershipId
        and t.active = true
        and s = :serviceTypeCode
        and not exists (
          select a.id from AppointmentEntity a
          where a.technicianId = t.id
            and a.status = com.keyloop.scheduler.appointment.entity.AppointmentStatus.CONFIRMED
            and a.startTime < :slotEnd
            and a.endTime > :slotStart)
      """)
  List<UUID> findFreeTechnicianIds(
      @Param("dealershipId") UUID dealershipId,
      @Param("serviceTypeCode") String serviceTypeCode,
      @Param("slotStart") Instant slotStart,
      @Param("slotEnd") Instant slotEnd);

  @Query(
      """
      select a from AppointmentEntity a
      where a.technicianId in :technicianIds
        and a.status = com.keyloop.scheduler.appointment.entity.AppointmentStatus.CONFIRMED
        and a.startTime >= :dayStart
        and a.startTime < :dayEnd
      """)
  List<AppointmentEntity> findConfirmedOnDayForTechnicians(
      @Param("technicianIds") Collection<UUID> technicianIds,
      @Param("dayStart") Instant dayStart,
      @Param("dayEnd") Instant dayEnd);

  @Query(
      """
      select count(a) from AppointmentEntity a
      where a.vehicleId = :vehicleId
        and a.status = com.keyloop.scheduler.appointment.entity.AppointmentStatus.CONFIRMED
        and a.startTime < :slotEnd
        and a.endTime > :slotStart
      """)
  long countVehicleOverlaps(
      @Param("vehicleId") UUID vehicleId,
      @Param("slotStart") Instant slotStart,
      @Param("slotEnd") Instant slotEnd);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select a from AppointmentEntity a where a.id = :id")
  Optional<AppointmentEntity> findForUpdate(@Param("id") UUID id);

  List<AppointmentEntity>
      findByDealershipIdAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTime(
          UUID dealershipId, Instant from, Instant to);
}
