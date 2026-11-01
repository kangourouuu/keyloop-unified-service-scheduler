package com.keyloop.scheduler.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TimeSlotUtilsTest {

  private static final ZoneId LONDON = ZoneId.of("Europe/London");
  private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
  private static final LocalTime EIGHT = LocalTime.of(8, 0);
  private static final LocalTime SIX_PM = LocalTime.of(18, 0);
  private static final Instant LONG_AGO = Instant.parse("2000-01-01T00:00:00Z");
  private static final LocalDate WINTER_DAY = LocalDate.of(2030, 1, 7);

  @Test
  void should_return_half_hour_starts_that_fit_before_close() {
    List<Instant> starts =
        TimeSlotUtils.candidateStarts(WINTER_DAY, LONDON, EIGHT, SIX_PM, 60, LONG_AGO);

    assertThat(starts).hasSize(19);
    assertThat(starts.getFirst()).isEqualTo(Instant.parse("2030-01-07T08:00:00Z"));
    assertThat(starts.getLast()).isEqualTo(Instant.parse("2030-01-07T17:00:00Z"));
  }

  @Test
  void should_start_at_bst_offset_on_london_summer_time_day() {
    List<Instant> starts =
        TimeSlotUtils.candidateStarts(
            LocalDate.of(2026, 3, 29), LONDON, EIGHT, SIX_PM, 60, LONG_AGO);

    assertThat(starts.getFirst()).isEqualTo(Instant.parse("2026-03-29T07:00:00Z"));
  }

  @Test
  void should_shift_new_york_opening_when_daylight_saving_starts() {
    LocalDate beforeDst = LocalDate.of(2026, 3, 7);
    LocalDate afterDst = LocalDate.of(2026, 3, 8);
    LocalTime nineToFive = LocalTime.of(17, 0);

    Instant firstBefore =
        TimeSlotUtils.candidateStarts(beforeDst, NEW_YORK, EIGHT, nineToFive, 60, LONG_AGO)
            .getFirst();
    Instant firstAfter =
        TimeSlotUtils.candidateStarts(afterDst, NEW_YORK, EIGHT, nineToFive, 60, LONG_AGO)
            .getFirst();

    assertThat(firstBefore).isEqualTo(Instant.parse("2026-03-07T13:00:00Z"));
    assertThat(firstAfter).isEqualTo(Instant.parse("2026-03-08T12:00:00Z"));
  }

  @Test
  void should_drop_starts_before_now_on_the_same_day() {
    Instant now = Instant.parse("2030-01-07T10:10:00Z");

    List<Instant> starts =
        TimeSlotUtils.candidateStarts(WINTER_DAY, LONDON, EIGHT, SIX_PM, 60, now);

    assertThat(starts.getFirst()).isEqualTo(Instant.parse("2030-01-07T10:30:00Z"));
  }

  @Test
  void should_return_no_starts_for_a_past_date() {
    Instant now = Instant.parse("2030-01-07T00:00:00Z");

    List<Instant> starts =
        TimeSlotUtils.candidateStarts(LocalDate.of(2030, 1, 6), LONDON, EIGHT, SIX_PM, 60, now);

    assertThat(starts).isEmpty();
  }

  @Test
  void should_accept_a_start_only_when_it_is_a_grid_start_that_ends_by_close() {
    assertThat(isBookable(LocalTime.of(9, 15), 60)).isFalse();
    assertThat(isBookable(LocalTime.of(9, 0, 30), 60)).isFalse();
    assertThat(isBookable(LocalTime.of(7, 30), 60)).isFalse();
    assertThat(isBookable(LocalTime.of(17, 0), 120)).isFalse();
    assertThat(isBookable(LocalTime.of(16, 0), 120)).isTrue();
    assertThat(isBookable(LocalTime.of(17, 0), 60)).isTrue();
  }

  @Test
  void should_accept_a_date_up_to_90_days_ahead_of_today() {
    Instant now = Instant.parse("2030-01-07T10:00:00Z");
    LocalDate today = LocalDate.of(2030, 1, 7);

    assertThat(TimeSlotUtils.isWithinHorizon(today.plusDays(90), LONDON, now)).isTrue();
    assertThat(TimeSlotUtils.isWithinHorizon(today.plusDays(91), LONDON, now)).isFalse();
  }

  private static boolean isBookable(LocalTime time, int durationMinutes) {
    Instant start = WINTER_DAY.atTime(time).atZone(LONDON).toInstant();
    boolean byArithmetic =
        TimeSlotUtils.isBookableStart(
            start, WINTER_DAY, LONDON, EIGHT, SIX_PM, durationMinutes, LONG_AGO);
    boolean byList =
        Set.copyOf(
                TimeSlotUtils.candidateStarts(
                    WINTER_DAY, LONDON, EIGHT, SIX_PM, durationMinutes, LONG_AGO))
            .contains(start);
    assertThat(byArithmetic).isEqualTo(byList);
    return byArithmetic;
  }
}
