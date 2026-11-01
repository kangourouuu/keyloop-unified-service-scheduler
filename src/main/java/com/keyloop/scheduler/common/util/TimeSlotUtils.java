package com.keyloop.scheduler.common.util;

import com.keyloop.scheduler.common.constants.ErrorMessageConstants;
import com.keyloop.scheduler.common.error.ErrorCode;
import com.keyloop.scheduler.common.error.SchedulerException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Stream;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TimeSlotUtils {

  private static final Duration GRID_STEP = Duration.ofMinutes(30);
  private static final int MAX_DAYS_AHEAD = 90;
  private static final int MAX_DAYS_BACK = 365;

  /**
   * Lists every half-hour start inside the dealership's opening hours on the given local date that
   * fits the whole service duration before closing and is not already in the past. {@link
   * #isBookableStart} applies the same rule to a single start for bookings, and a test asserts the
   * two agree, so grid, hours, past and DST rules cannot drift apart.
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
    return Stream.iterate(
            hours.start(),
            start -> !start.plus(duration).isAfter(hours.end()),
            start -> start.plus(GRID_STEP))
        .filter(start -> !start.isBefore(now))
        .toList();
  }

  /**
   * Tells whether the start is one of the {@link #candidateStarts} values, by arithmetic on the
   * opening window instead of a scan of the list: on the half-hour grid from opening, fitting the
   * whole duration before closing, and not in the past.
   */
  public static boolean isBookableStart(
      Instant start,
      LocalDate date,
      ZoneId zone,
      LocalTime open,
      LocalTime close,
      int durationMinutes,
      Instant now) {
    Window hours = openWindow(date, zone, open, close);
    Duration sinceOpening = Duration.between(hours.start(), start);
    return !start.isBefore(now)
        && !sinceOpening.isNegative()
        && sinceOpening.toNanos() % GRID_STEP.toNanos() == 0
        && !start.plus(Duration.ofMinutes(durationMinutes)).isAfter(hours.end());
  }

  /**
   * Tells whether the date is at most 90 days after today in the dealership's zone. Bounds how far
   * ahead capacity can be held.
   */
  public static boolean isWithinHorizon(LocalDate date, ZoneId zone, Instant now) {
    return !date.isAfter(LocalDate.ofInstant(now, zone).plusDays(MAX_DAYS_AHEAD));
  }

  /** Fails with KUSS_VALIDATION_FAILED when the date is more than 90 days ahead. */
  public static void requireWithinHorizon(LocalDate date, ZoneId zone, Instant now) {
    if (!isWithinHorizon(date, zone, now)) {
      throw new SchedulerException(
          ErrorCode.KUSS_VALIDATION_FAILED, ErrorMessageConstants.DATE_OUT_OF_HORIZON);
    }
  }

  /** Tells whether the date is before today in the dealership's zone. */
  public static boolean isPast(LocalDate date, ZoneId zone, Instant now) {
    return date.isBefore(LocalDate.ofInstant(now, zone));
  }

  /**
   * Tells whether the date is at most 365 days before today in the dealership's zone. Bounds how
   * far back the day list reads, and keeps far dates out of the timestamptz range.
   */
  public static boolean isWithinHistory(LocalDate date, ZoneId zone, Instant now) {
    return !date.isBefore(LocalDate.ofInstant(now, zone).minusDays(MAX_DAYS_BACK));
  }

  /**
   * Returns the instants that bound one local calendar day of the zone, so a 23 or 25 hour DST day
   * is covered exactly.
   */
  public static Window localDay(LocalDate date, ZoneId zone) {
    return Window.builder()
        .start(date.atStartOfDay(zone).toInstant())
        .end(date.plusDays(1).atStartOfDay(zone).toInstant())
        .build();
  }

  /** Returns the instants at which the dealership opens and closes on the given local date. */
  public static Window openWindow(LocalDate date, ZoneId zone, LocalTime open, LocalTime close) {
    return Window.builder()
        .start(date.atTime(open).atZone(zone).toInstant())
        .end(date.atTime(close).atZone(zone).toInstant())
        .build();
  }

  @Builder
  public record Window(Instant start, Instant end) {}
}
