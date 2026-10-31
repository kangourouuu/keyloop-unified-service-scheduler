package com.keyloop.scheduler.appointment.util;

import com.keyloop.scheduler.appointment.AppointmentEntity;
import com.keyloop.scheduler.appointment.dto.CandidateDto;
import com.keyloop.scheduler.appointment.dto.TechnicianLoadDto;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;

public final class CandidateSelectionUtils {

  private CandidateSelectionUtils() {}

  public static List<TechnicianLoadDto> loads(
      List<UUID> technicianIds, List<AppointmentEntity> dayAppointments) {
    Map<UUID, Long> minutesByTechnician =
        dayAppointments.stream()
            .collect(
                Collectors.groupingBy(
                    AppointmentEntity::getTechnicianId,
                    Collectors.summingLong(appointment ->
                        Duration.between(appointment.getStartTime(), appointment.getEndTime())
                            .toMinutes())));
    return technicianIds.stream()
        .map(id ->
            TechnicianLoadDto.builder()
                .technicianId(id)
                .bookedMinutes(minutesByTechnician.getOrDefault(id, 0L))
                .build())
        .toList();
  }

  public static CandidateDto pick(
      List<UUID> freeBayIds, List<TechnicianLoadDto> freeTechnicians, RandomGenerator random) {
    long fewestMinutes =
        freeTechnicians.stream().mapToLong(TechnicianLoadDto::bookedMinutes).min().orElseThrow();
    List<UUID> leastLoaded =
        freeTechnicians.stream()
            .filter(technician -> technician.bookedMinutes() == fewestMinutes)
            .map(TechnicianLoadDto::technicianId)
            .toList();
    return CandidateDto.builder()
        .serviceBayId(freeBayIds.get(random.nextInt(freeBayIds.size())))
        .technicianId(leastLoaded.get(random.nextInt(leastLoaded.size())))
        .build();
  }
}
