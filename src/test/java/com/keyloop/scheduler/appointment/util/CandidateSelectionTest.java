package com.keyloop.scheduler.appointment.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.keyloop.scheduler.appointment.dto.CandidateDto;
import com.keyloop.scheduler.appointment.dto.TechnicianLoadDto;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class CandidateSelectionTest {

  private static final UUID BAY_1 = UUID.randomUUID();
  private static final UUID BAY_2 = UUID.randomUUID();
  private static final UUID BAY_3 = UUID.randomUUID();
  private static final UUID LIGHT = UUID.randomUUID();
  private static final UUID HEAVY = UUID.randomUUID();
  private static final UUID TIE_A = UUID.randomUUID();
  private static final UUID TIE_B = UUID.randomUUID();

  @Test
  void should_pick_the_technician_with_fewest_booked_minutes() {
    List<TechnicianLoadDto> technicians = List.of(load(HEAVY, 180), load(LIGHT, 30));

    CandidateDto pick = CandidateSelectionUtils.pick(List.of(BAY_1), technicians, new Random(42));

    assertThat(pick.technicianId()).isEqualTo(LIGHT);
  }

  @Test
  void should_pick_only_among_tied_least_loaded_technicians() {
    List<TechnicianLoadDto> technicians =
        List.of(load(TIE_A, 60), load(TIE_B, 60), load(HEAVY, 120));
    Random random = new Random(42);

    Set<UUID> picked = pickedTechnicians(technicians, random);

    assertThat(picked).containsExactlyInAnyOrder(TIE_A, TIE_B);
  }

  @Test
  void should_pick_every_free_bay_across_many_picks() {
    Random random = new Random(42);
    Set<UUID> bays =
        java.util.stream.IntStream.range(0, 100)
            .mapToObj(i ->
                CandidateSelectionUtils.pick(
                        List.of(BAY_1, BAY_2, BAY_3), List.of(load(LIGHT, 0)), random)
                    .serviceBayId())
            .collect(Collectors.toSet());

    assertThat(bays).containsExactlyInAnyOrder(BAY_1, BAY_2, BAY_3);
  }

  private static Set<UUID> pickedTechnicians(List<TechnicianLoadDto> technicians, Random random) {
    return java.util.stream.IntStream.range(0, 100)
        .mapToObj(
            i -> CandidateSelectionUtils.pick(List.of(BAY_1), technicians, random).technicianId())
        .collect(Collectors.toSet());
  }

  private static TechnicianLoadDto load(UUID technicianId, long bookedMinutes) {
    return TechnicianLoadDto.builder()
        .technicianId(technicianId)
        .bookedMinutes(bookedMinutes)
        .build();
  }
}
