package com.keyloop.scheduler.appointment.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Random;
import org.junit.jupiter.api.Test;

class BookingDeadlineTest {

  private static final Instant START = Instant.parse("2026-10-31T10:00:00Z");

  @Test
  void should_expire_ten_seconds_after_the_first_attempt() {
    Clock clock = mock(Clock.class);
    when(clock.instant()).thenReturn(START, START.plusMillis(9_999), START.plusSeconds(10));
    BookingDeadline deadline = BookingDeadline.startingNow(clock);

    assertThat(deadline.isExpired()).isFalse();
    assertThat(deadline.isExpired()).isTrue();
  }

  @Test
  void should_wait_longer_than_a_knot_drains_after_the_first_deadlock() {
    BookingDeadline deadline = BookingDeadline.startingNow(Clock.fixed(START, ZoneOffset.UTC));

    Duration wait = deadline.nextDeadlockBackoff(new Random(42)).orElseThrow();

    assertThat(wait).isBetween(Duration.ofMillis(1_600), Duration.ofMillis(3_200));
  }

  @Test
  void should_double_the_wait_per_deadlock_up_to_the_cap() {
    BookingDeadline deadline = BookingDeadline.startingNow(Clock.fixed(START, ZoneOffset.UTC));
    Random random = new Random(42);
    deadline.nextDeadlockBackoff(random);

    Duration second = deadline.nextDeadlockBackoff(random).orElseThrow();
    Duration third = deadline.nextDeadlockBackoff(random).orElseThrow();

    assertThat(second).isBetween(Duration.ofMillis(3_200), Duration.ofMillis(6_400));
    assertThat(third).isBetween(Duration.ofMillis(3_200), Duration.ofMillis(6_400));
  }

  @Test
  void should_not_wait_when_the_wait_would_end_past_the_deadline() {
    Clock clock = mock(Clock.class);
    when(clock.instant()).thenReturn(START, START.plusMillis(9_950));
    BookingDeadline deadline = BookingDeadline.startingNow(clock);

    assertThat(deadline.nextDeadlockBackoff(new Random(42))).isEmpty();
  }
}
