package com.keyloop.scheduler.availability.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.keyloop.scheduler.availability.dto.BusyIntervalDto;
import com.keyloop.scheduler.availability.dto.SlotDto;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AvailabilitySweepTest {

  private static final ZoneId LONDON = ZoneId.of("Europe/London");
  private static final Instant NINE = Instant.parse("2030-01-07T09:00:00Z");
  private static final Instant NINE_THIRTY = Instant.parse("2030-01-07T09:30:00Z");
  private static final Instant TEN = Instant.parse("2030-01-07T10:00:00Z");
  private static final UUID BAY_1 = UUID.randomUUID();
  private static final UUID BAY_2 = UUID.randomUUID();
  private static final UUID BAY_3 = UUID.randomUUID();
  private static final UUID TECH_1 = UUID.randomUUID();
  private static final UUID TECH_2 = UUID.randomUUID();
  private static final UUID TECH_3 = UUID.randomUUID();
  private static final UUID TECH_4 = UUID.randomUUID();
  private static final UUID OTHER = UUID.randomUUID();

  @Test
  void should_cap_capacity_at_bay_count_when_bays_are_scarcer_than_technicians() {
    List<SlotDto> slots =
        AvailabilitySweepUtils.sweep(
            List.of(NINE),
            60,
            LONDON,
            Set.of(BAY_1, BAY_2, BAY_3),
            Set.of(TECH_1, TECH_2, TECH_3, TECH_4),
            List.of());

    assertThat(slots).extracting(SlotDto::availableCapacity).containsExactly(3);
  }

  @Test
  void should_cap_capacity_at_technician_count_when_technicians_are_scarcer() {
    List<SlotDto> slots =
        AvailabilitySweepUtils.sweep(
            List.of(NINE),
            60,
            LONDON,
            Set.of(BAY_1, BAY_2, BAY_3),
            Set.of(TECH_1, TECH_2),
            List.of());

    assertThat(slots).extracting(SlotDto::availableCapacity).containsExactly(2);
  }

  @Test
  void should_reduce_capacity_only_for_overlapping_starts_and_not_for_back_to_back_ones() {
    List<BusyIntervalDto> busy = List.of(busy(BAY_1, TECH_1, NINE, TEN));

    List<SlotDto> slots =
        AvailabilitySweepUtils.sweep(
            List.of(NINE, NINE_THIRTY, TEN),
            60,
            LONDON,
            Set.of(BAY_1, BAY_2, BAY_3),
            Set.of(TECH_1, TECH_2),
            busy);

    assertThat(slots).extracting(SlotDto::availableCapacity).containsExactly(1, 1, 2);
  }

  @Test
  void should_reduce_only_by_busy_bays_when_busy_technician_is_outside_qualified_set() {
    List<BusyIntervalDto> busy = List.of(busy(BAY_1, OTHER, NINE, TEN));

    List<SlotDto> slots =
        AvailabilitySweepUtils.sweep(
            List.of(NINE), 60, LONDON, Set.of(BAY_1, BAY_2), Set.of(TECH_1, TECH_2, TECH_3), busy);

    assertThat(slots).extracting(SlotDto::availableCapacity).containsExactly(1);
  }

  @Test
  void should_ignore_busy_intervals_from_another_dealership() {
    List<BusyIntervalDto> busy = List.of(busy(OTHER, OTHER, NINE, TEN));

    List<SlotDto> slots =
        AvailabilitySweepUtils.sweep(
            List.of(NINE), 60, LONDON, Set.of(BAY_1, BAY_2), Set.of(TECH_1, TECH_2), busy);

    assertThat(slots).extracting(SlotDto::availableCapacity).containsExactly(2);
  }

  @Test
  void should_omit_a_slot_when_every_bay_is_busy() {
    List<BusyIntervalDto> busy = List.of(busy(BAY_1, TECH_1, NINE, TEN));

    List<SlotDto> slots =
        AvailabilitySweepUtils.sweep(
            List.of(NINE), 60, LONDON, Set.of(BAY_1), Set.of(TECH_1, TECH_2), busy);

    assertThat(slots).isEmpty();
  }

  private static BusyIntervalDto busy(UUID bayId, UUID techId, Instant start, Instant end) {
    return BusyIntervalDto.builder()
        .serviceBayId(bayId)
        .technicianId(techId)
        .startTime(start)
        .endTime(end)
        .build();
  }
}
