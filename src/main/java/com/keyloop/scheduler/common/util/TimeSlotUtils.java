package com.keyloop.scheduler.common.util;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import lombok.Builder;

public final class TimeSlotUtils {

  private static final Duration GRID_STEP = Duration.ofMinutes(30);
  private static final int MAX_DAYS_AHEAD = 90;

  private TimeSlotUtils() {}

  /**
   * Lists every half-hour start inside the dealership's opening hours on the given local date that
   * fits the whole service duration before closing and is not already in the past. Booking validity
   * is decided by membership in this list, so grid, hours, past and DST rules have one source.
   */
  public static List<Instant> candidateStarts(
      LocalDate date,
      ZoneId zone,
      LocalTime open,
      LocalTime close,
      int durationMinutes,
      Instant now) {
    Window hours = openWindow(date, zone, open, close);
    Duration duration = Duration.ofMinutes(durationMinutes);
    List<Instant> starts = new ArrayList<>();
    for (Instant start = hours.start();
        !start.plus(duration).isAfter(hours.end());
        start = start.plus(GRID_STEP)) {
      if (!start.isBefore(now)) {
        starts.add(start);
      }
    }
    return starts;
  }

  /**
   * Tells whether the date is at most 90 days after today in the dealership's zone. Bounds how far
   * ahead capacity can be held.
   */
  public static boolean isWithinHorizon(LocalDate date, ZoneId zone, Instant now) {
    return !date.isAfter(LocalDate.ofInstant(now, zone).plusDays(MAX_DAYS_AHEAD));
  }

  public static Window localDay(LocalDate date, ZoneId zone) {
    return Window.builder()
        .start(date.atStartOfDay(zone).toInstant())
        .end(date.plusDays(1).atStartOfDay(zone).toInstant())
        .build();
  }

  public static Window openWindow(LocalDate date, ZoneId zone, LocalTime open, LocalTime close) {
    return Window.builder()
        .start(date.atTime(open).atZone(zone).toInstant())
        .end(date.atTime(close).atZone(zone).toInstant())
        .build();
  }

  @Builder
  public record Window(Instant start, Instant end) {}
}
