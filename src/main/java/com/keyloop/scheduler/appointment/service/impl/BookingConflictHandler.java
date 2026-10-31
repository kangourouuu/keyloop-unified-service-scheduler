package com.keyloop.scheduler.appointment.service.impl;

import com.keyloop.scheduler.appointment.util.BookingErrorUtils;
import com.keyloop.scheduler.common.util.SqlErrorUtils;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
class BookingConflictHandler {

  private static final String VEHICLE_OVERLAP_CONSTRAINT = "excl_appointment_vehicle_overlap";
  private static final String BAY_OVERLAP_CONSTRAINT = "excl_appointment_bay_overlap";
  private static final String DEADLOCK_STATE = "40P01";
  private static final Set<String> RETRYABLE_STATES = Set.of("23P01", DEADLOCK_STATE);

  private final BookingMetrics bookingMetrics;

  /**
   * Classifies a failed booking attempt. A vehicle overlap fails the request, a statement timeout
   * fails it as service busy, and any other non-retryable error is rethrown. A bay or technician
   * exclusion conflict or a deadlock is counted and returns so the caller retries against a fresh
   * snapshot; a deadlock victim first waits out the randomized backoff.
   */
  void handle(DataAccessException ex, BookingDeadline deadline, boolean hasNextAttempt) {
    String constraint = SqlErrorUtils.constraintName(ex);
    String state = SqlErrorUtils.sqlState(ex);
    if (VEHICLE_OVERLAP_CONSTRAINT.equals(constraint)) {
      throw BookingErrorUtils.vehicleAlreadyBooked();
    }
    if (ex instanceof QueryTimeoutException) {
      log.warn("Booking aborted, database statement timed out: {}", ex.getMessage());
      throw BookingErrorUtils.serviceBusy();
    }
    if (!RETRYABLE_STATES.contains(state)) {
      throw ex;
    }
    bookingMetrics.countRetry(conflictType(constraint, state));
    if (DEADLOCK_STATE.equals(state) && hasNextAttempt) {
      backOffAfterDeadlock(deadline);
    }
  }

  /**
   * Waits outside any transaction before a deadlock victim retries, for the randomized backoff that
   * {@link BookingDeadline} paces. Gives up as service busy when that wait would pass the request
   * deadline.
   */
  private static void backOffAfterDeadlock(BookingDeadline deadline) {
    Duration wait =
        deadline
            .nextDeadlockBackoff(ThreadLocalRandom.current())
            .orElseThrow(BookingErrorUtils::deadlineReached);
    try {
      Thread.sleep(wait);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw BookingErrorUtils.bookingNotCompleted();
    }
  }

  private static String conflictType(String constraint, String state) {
    if (DEADLOCK_STATE.equals(state)) {
      return BookingMetrics.CONFLICT_DEADLOCK;
    }
    return BAY_OVERLAP_CONSTRAINT.equals(constraint)
        ? BookingMetrics.CONFLICT_BAY
        : BookingMetrics.CONFLICT_TECHNICIAN;
  }
}
