package com.keyloop.scheduler.availability.util;

import com.keyloop.scheduler.availability.dto.BusyIntervalDto;
import com.keyloop.scheduler.availability.dto.SlotDto;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AvailabilitySweepUtils {

  /**
   * Computes the capacity of every candidate start: the smaller of the free bays and the free
   * qualified technicians over the whole service duration, where a resource is busy when a
   * confirmed appointment overlaps the slot. Starts with no capacity are dropped.
   */
  public static List<SlotDto> sweep(
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
    List<BusyIntervalDto> overlapping =
        busy.stream()
            .filter(interval ->
                interval.startTime().isBefore(end) && start.isBefore(interval.endTime()))
            .toList();
    Set<UUID> busyBays =
        overlapping.stream().map(BusyIntervalDto::serviceBayId).collect(Collectors.toSet());
    Set<UUID> busyTechnicians =
        overlapping.stream().map(BusyIntervalDto::technicianId).collect(Collectors.toSet());
    long freeBays = bayIds.stream().filter(id -> !busyBays.contains(id)).count();
    long freeTechnicians =
        technicianIds.stream().filter(id -> !busyTechnicians.contains(id)).count();
    return (int) Math.min(freeBays, freeTechnicians);
  }
}
