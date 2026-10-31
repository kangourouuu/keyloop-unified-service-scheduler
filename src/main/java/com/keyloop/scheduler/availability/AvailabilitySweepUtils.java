package com.keyloop.scheduler.availability;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class AvailabilitySweepUtils {

  private AvailabilitySweepUtils() {}

  static List<SlotDto> sweep(
      List<Instant> starts,
      int durationMinutes,
      ZoneId zone,
      Set<UUID> bayIds,
      Set<UUID> technicianIds,
      List<BusyIntervalDto> busy) {
    Duration duration = Duration.ofMinutes(durationMinutes);
    return starts.stream()
        .map(start ->
            SlotDto.builder()
                .startTime(start.atZone(zone).toOffsetDateTime())
                .availableCapacity(
                    capacityAt(start, start.plus(duration), bayIds, technicianIds, busy))
                .build())
        .filter(slot -> slot.availableCapacity() > 0)
        .toList();
  }

  private static int capacityAt(
      Instant start,
      Instant end,
      Set<UUID> bayIds,
      Set<UUID> technicianIds,
      List<BusyIntervalDto> busy) {
    Set<UUID> busyBays = new HashSet<>();
    Set<UUID> busyTechnicians = new HashSet<>();
    for (BusyIntervalDto interval : busy) {
      if (interval.startTime().isBefore(end) && start.isBefore(interval.endTime())) {
        busyBays.add(interval.serviceBayId());
        busyTechnicians.add(interval.technicianId());
      }
    }
    long freeBays = bayIds.stream().filter(id -> !busyBays.contains(id)).count();
    long freeTechnicians =
        technicianIds.stream().filter(id -> !busyTechnicians.contains(id)).count();
    return (int) Math.min(freeBays, freeTechnicians);
  }
}
