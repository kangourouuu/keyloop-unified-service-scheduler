package com.keyloop.scheduler.appointment.service.impl;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Paces one booking request: the 10 s wall-clock budget and the randomized backoff of a deadlock
 * victim. The backoff is sized for a PostgreSQL {@code deadlock_timeout} of 100 ms, which is
 * therefore a required setting for every deployment (docker-compose and the Testcontainers database
 * set it); with the 1 s default the knot of a 20-way burst would drain far slower than the backoff
 * assumes.
 */
final class BookingDeadline {

  private static final Duration BUDGET = Duration.ofSeconds(10);
  private static final long DEADLOCK_BACKOFF_MILLIS = 1600;
  private static final int MAX_DEADLOCK_BACKOFF_SHIFT = 2;

  private final Clock clock;
  private final Instant expiresAt;
  private int deadlocks;

  private BookingDeadline(Clock clock) {
    this.clock = clock;
    this.expiresAt = clock.instant().plus(BUDGET);
  }

  /** Starts the 10 s wall-clock budget of one booking request. */
  static BookingDeadline startingNow(Clock clock) {
    return new BookingDeadline(clock);
  }

  boolean isExpired() {
    return !clock.instant().isBefore(expiresAt);
  }

  /**
   * Returns the wait before a deadlock victim retries. PostgreSQL breaks only one exclusion-index
   * wait cycle per deadlock_timeout, so N inserters knotted on one slot need about N times
   * deadlock_timeout to drain; a victim that returned sooner would re-insert a conflicting index
   * entry and keep the knot alive. The first wait is 1.6-3.2 s, longer than a 20-request knot takes
   * to drain at a 100 ms deadlock_timeout, and it doubles with each deadlock of this request up to
   * 3.2-6.4 s, randomized so victims return spread out. Returns empty when the wait would end at or
   * past the deadline, so the request gives up instead of sleeping in vain.
   */
  Optional<Duration> nextDeadlockBackoff(RandomGenerator random) {
    deadlocks++;
    long ceiling = DEADLOCK_BACKOFF_MILLIS << Math.min(deadlocks, MAX_DEADLOCK_BACKOFF_SHIFT);
    Duration wait = Duration.ofMillis(ceiling / 2 + random.nextLong(ceiling / 2 + 1));
    return clock.instant().plus(wait).isBefore(expiresAt) ? Optional.of(wait) : Optional.empty();
  }
}
