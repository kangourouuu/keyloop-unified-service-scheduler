package com.keyloop.scheduler.appointment.service.impl;

import com.keyloop.scheduler.appointment.dto.AppointmentDto;
import com.keyloop.scheduler.appointment.dto.AppointmentResultDto;
import com.keyloop.scheduler.appointment.dto.BookAppointmentDto;
import com.keyloop.scheduler.appointment.entity.AppointmentEntity;
import com.keyloop.scheduler.appointment.entity.AppointmentStatus;
import com.keyloop.scheduler.appointment.mapper.AppointmentMapper;
import com.keyloop.scheduler.appointment.repository.AppointmentRepository;
import com.keyloop.scheduler.appointment.service.AppointmentService;
import com.keyloop.scheduler.appointment.service.IdempotencyService;
import com.keyloop.scheduler.appointment.util.BookingErrorUtils;
import com.keyloop.scheduler.catalog.entity.DealershipEntity;
import com.keyloop.scheduler.catalog.service.CatalogService;
import com.keyloop.scheduler.common.constants.ApiPathConstants;
import com.keyloop.scheduler.common.constants.ErrorMessageConstants;
import com.keyloop.scheduler.common.constants.MetricConstants;
import com.keyloop.scheduler.common.constants.RequestConstants;
import com.keyloop.scheduler.common.error.ErrorCode;
import com.keyloop.scheduler.common.error.SchedulerException;
import com.keyloop.scheduler.common.util.TimeSlotUtils;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
class AppointmentServiceImpl implements AppointmentService {

  private static final int MAX_ATTEMPTS = 100;

  private final Clock clock;
  private final CatalogService catalogService;
  private final AppointmentRepository appointmentRepository;
  private final AppointmentMapper appointmentMapper;
  private final IdempotencyService idempotencyService;
  private final PlatformTransactionManager transactionManager;
  private final BookingAttemptHelper bookingAttemptHelper;
  private final BookingConflictHelper conflictHelper;
  private final BookingMetricsHelper bookingMetricsHelper;

  /**
   * Books the first free bay and qualified technician pair for the whole service duration. Each
   * attempt runs in its own transaction with a fresh persistence context, so an
   * exclusion-constraint conflict is retried against a new snapshot until no free pair is left. A
   * vehicle already booked for an overlapping time is rejected before capacity is checked. A
   * replayed Idempotency-Key returns the stored result. A request that finds no free database
   * connection, or is still retrying 10 s after its first attempt, fails as service busy; its claim
   * was rolled back, so the client can retry with the same key.
   */
  @Override
  public AppointmentResultDto book(BookAppointmentDto request, String idempotencyKey) {
    String result = ErrorCode.KUSS_INTERNAL_ERROR.name();
    try {
      AppointmentResultDto booked = bookWithRetry(request, idempotencyKey);
      result = MetricConstants.RESULT_CONFIRMED;
      return booked;
    } catch (SchedulerException ex) {
      result = ex.getCode().name();
      throw ex;
    } finally {
      bookingMetricsHelper.recordBooking(request, result);
    }
  }

  private TransactionTemplate bookingTransaction() {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setTimeout(RequestConstants.TRANSACTION_TIMEOUT_SECONDS);
    return template;
  }

  private AppointmentResultDto bookWithRetry(BookAppointmentDto request, String idempotencyKey) {
    BookingDeadline deadline = BookingDeadline.startingNow(clock);
    for (int attempt = 1; attempt <= MAX_ATTEMPTS && !deadline.isExpired(); attempt++) {
      try {
        return bookingTransaction()
            .execute(status -> bookingAttemptHelper.bookOnce(request, idempotencyKey));
      } catch (DataAccessException ex) {
        conflictHelper.handle(ex, deadline, attempt < MAX_ATTEMPTS);
      } catch (CannotCreateTransactionException ex) {
        log.warn("Booking aborted, no database connection: {}", ex.getMessage());
        throw BookingErrorUtils.serviceBusy();
      }
    }
    if (deadline.isExpired()) {
      throw BookingErrorUtils.deadlineReached();
    }
    log.error(
        "Booking retry cap {} reached, dealershipId={}", MAX_ATTEMPTS, request.dealershipId());
    throw BookingErrorUtils.bookingNotCompleted();
  }

  /**
   * Cancels a confirmed appointment that has not started yet with one atomic conditional update, so
   * no row is locked by a read first. A second canceller waits for the first to commit, matches
   * nothing, and then sees the cancelled state. Cancelling an already cancelled appointment
   * succeeds with its current state, even when its start is in the past.
   */
  @Override
  @Transactional(timeout = RequestConstants.TRANSACTION_TIMEOUT_SECONDS)
  public AppointmentResultDto cancel(UUID appointmentId, String idempotencyKey) {
    Optional<AppointmentResultDto> replay =
        idempotencyService.claim(idempotencyKey, cancelRequest(appointmentId));
    if (replay.isPresent()) {
      return replay.get();
    }
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    AppointmentResultDto result =
        AppointmentResultDto.builder()
            .httpStatus(200)
            .appointment(cancelAtomically(appointmentId, now))
            .build();
    idempotencyService.complete(idempotencyKey, result);
    return result;
  }

  private AppointmentDto cancelAtomically(UUID appointmentId, Instant now) {
    int cancelled = appointmentRepository.cancelIfConfirmedAndNotStarted(appointmentId, now);
    AppointmentEntity appointment =
        appointmentRepository
            .findById(appointmentId)
            .orElseThrow(BookingErrorUtils::appointmentNotFound);
    if (cancelled == 0 && appointment.getStatus() == AppointmentStatus.CONFIRMED) {
      throw new SchedulerException(
          ErrorCode.KUSS_APPOINTMENT_ALREADY_STARTED,
          ErrorMessageConstants.APPOINTMENT_ALREADY_STARTED);
    }
    return appointmentMapper.toDto(appointment);
  }

  @Override
  @Transactional(readOnly = true, timeout = RequestConstants.TRANSACTION_TIMEOUT_SECONDS)
  public AppointmentDto getAppointment(UUID appointmentId) {
    return appointmentRepository
        .findById(appointmentId)
        .map(appointmentMapper::toDto)
        .orElseThrow(BookingErrorUtils::appointmentNotFound);
  }

  /**
   * Lists the appointments that start on one local day of the dealership, cancelled ones included,
   * in start order. Only days from 365 days back to 90 days ahead are accepted.
   */
  @Override
  @Transactional(readOnly = true, timeout = RequestConstants.TRANSACTION_TIMEOUT_SECONDS)
  public List<AppointmentDto> listForDay(UUID dealershipId, LocalDate date) {
    DealershipEntity dealership = catalogService.requireDealership(dealershipId);
    ZoneId zone = ZoneId.of(dealership.getTimezone());
    requireListableDate(date, zone, clock.instant());
    TimeSlotUtils.Window day = TimeSlotUtils.localDay(date, zone);
    return appointmentRepository
        .findByDealershipIdAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTime(
            dealershipId, day.start(), day.end())
        .stream()
        .map(appointmentMapper::toDto)
        .toList();
  }

  private static void requireListableDate(LocalDate date, ZoneId zone, Instant now) {
    if (!TimeSlotUtils.isWithinHistory(date, zone, now)) {
      throw new SchedulerException(
          ErrorCode.KUSS_VALIDATION_FAILED, ErrorMessageConstants.DATE_BEYOND_HISTORY);
    }
    TimeSlotUtils.requireWithinHorizon(date, zone, now);
  }

  private static String cancelRequest(UUID appointmentId) {
    return HttpMethod.POST.name()
        + " "
        + ApiPathConstants.APPOINTMENTS
        + "/"
        + appointmentId
        + ApiPathConstants.CANCEL;
  }
}
