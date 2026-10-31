package com.keyloop.scheduler.appointment.service.impl;

import com.keyloop.scheduler.appointment.dto.AppointmentDto;
import com.keyloop.scheduler.appointment.dto.AppointmentResultDto;
import com.keyloop.scheduler.appointment.dto.BookAppointmentDto;
import com.keyloop.scheduler.appointment.dto.CandidateDto;
import com.keyloop.scheduler.appointment.dto.TechnicianLoadDto;
import com.keyloop.scheduler.appointment.entity.AppointmentEntity;
import com.keyloop.scheduler.appointment.entity.AppointmentStatus;
import com.keyloop.scheduler.appointment.mapper.AppointmentMapper;
import com.keyloop.scheduler.appointment.repository.AppointmentRepository;
import com.keyloop.scheduler.appointment.repository.AppointmentWriteRepository;
import com.keyloop.scheduler.appointment.service.AppointmentService;
import com.keyloop.scheduler.appointment.service.IdempotencyService;
import com.keyloop.scheduler.appointment.util.CandidateSelectionUtils;
import com.keyloop.scheduler.catalog.entity.DealershipEntity;
import com.keyloop.scheduler.catalog.entity.ServiceTypeEntity;
import com.keyloop.scheduler.catalog.entity.VehicleEntity;
import com.keyloop.scheduler.catalog.repository.DealershipRepository;
import com.keyloop.scheduler.catalog.repository.ServiceTypeRepository;
import com.keyloop.scheduler.catalog.repository.VehicleRepository;
import com.keyloop.scheduler.common.constants.ErrorMessageConstants;
import com.keyloop.scheduler.common.constants.MetricConstants;
import com.keyloop.scheduler.common.error.ErrorCode;
import com.keyloop.scheduler.common.error.SchedulerException;
import com.keyloop.scheduler.common.util.SqlErrorUtils;
import com.keyloop.scheduler.common.util.TimeSlotUtils;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
class AppointmentServiceImpl implements AppointmentService {

  private static final int MAX_ATTEMPTS = 100;
  private static final int TRANSACTION_TIMEOUT_SECONDS = 5;
  private static final String VEHICLE_OVERLAP_CONSTRAINT = "excl_appointment_vehicle_overlap";
  private static final String BAY_OVERLAP_CONSTRAINT = "excl_appointment_bay_overlap";
  private static final String CONFLICT_BAY = "bay";
  private static final String CONFLICT_TECHNICIAN = "technician";
  private static final String CONFLICT_DEADLOCK = "deadlock";
  private static final String DEADLOCK_STATE = "40P01";
  private static final Set<String> RETRYABLE_STATES = Set.of("23P01", DEADLOCK_STATE);
  private static final String CONFIRMED_RESULT = "confirmed";
  private static final String UNKNOWN_SERVICE_TYPE = "unknown";
  private static final Set<String> BOOKING_RESULTS =
      Set.of(
          CONFIRMED_RESULT,
          ErrorCode.KUSS_VALIDATION_FAILED.name(),
          ErrorCode.KUSS_RESOURCE_NOT_FOUND.name(),
          ErrorCode.KUSS_INTERNAL_ERROR.name(),
          ErrorCode.KUSS_SERVICE_BUSY.name(),
          ErrorCode.KUSS_NO_CAPACITY.name(),
          ErrorCode.KUSS_VEHICLE_ALREADY_BOOKED.name(),
          ErrorCode.KUSS_IDEMPOTENCY_IN_FLIGHT.name(),
          ErrorCode.KUSS_IDEMPOTENCY_KEY_MISMATCH.name());
  private static final Set<String> TRACKED_SERVICE_TYPE_RESULTS =
      Set.of(
          CONFIRMED_RESULT,
          ErrorCode.KUSS_NO_CAPACITY.name(),
          ErrorCode.KUSS_VEHICLE_ALREADY_BOOKED.name());

  private final Clock clock;
  private final DealershipRepository dealershipRepository;
  private final VehicleRepository vehicleRepository;
  private final ServiceTypeRepository serviceTypeRepository;
  private final AppointmentRepository appointmentRepository;
  private final AppointmentWriteRepository writeRepository;
  private final AppointmentMapper appointmentMapper;
  private final IdempotencyService idempotencyService;
  private final TransactionTemplate transactionTemplate;
  private final Map<String, Counter> retryCounters;
  private final MeterRegistry meterRegistry;

  AppointmentServiceImpl(
      Clock clock,
      DealershipRepository dealershipRepository,
      VehicleRepository vehicleRepository,
      ServiceTypeRepository serviceTypeRepository,
      AppointmentRepository appointmentRepository,
      AppointmentWriteRepository writeRepository,
      AppointmentMapper appointmentMapper,
      IdempotencyService idempotencyService,
      PlatformTransactionManager transactionManager,
      MeterRegistry meterRegistry) {
    this.clock = clock;
    this.dealershipRepository = dealershipRepository;
    this.vehicleRepository = vehicleRepository;
    this.serviceTypeRepository = serviceTypeRepository;
    this.appointmentRepository = appointmentRepository;
    this.writeRepository = writeRepository;
    this.appointmentMapper = appointmentMapper;
    this.idempotencyService = idempotencyService;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.transactionTemplate.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
    this.retryCounters = registerRetryCounters(meterRegistry);
    this.meterRegistry = meterRegistry;
  }

  private static Map<String, Counter> registerRetryCounters(MeterRegistry meterRegistry) {
    return Stream.of(CONFLICT_BAY, CONFLICT_TECHNICIAN, CONFLICT_DEADLOCK)
        .collect(
            Collectors.toMap(
                Function.identity(),
                conflictType ->
                    Counter.builder(MetricConstants.BOOKING_GIST_RETRIES)
                        .tag(MetricConstants.TAG_CONFLICT_TYPE, conflictType)
                        .register(meterRegistry)));
  }

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
      result = CONFIRMED_RESULT;
      return booked;
    } catch (SchedulerException ex) {
      result = ex.getCode().name();
      throw ex;
    } finally {
      recordBooking(request, result);
    }
  }

  private void recordBooking(BookAppointmentDto request, String result) {
    String serviceType =
        TRACKED_SERVICE_TYPE_RESULTS.contains(result)
            ? request.serviceTypeCode()
            : UNKNOWN_SERVICE_TYPE;
    bookingCounter(serviceType, result).increment();
  }

  @PostConstruct
  void registerBookingMeters() {
    List<String> serviceTypeCodes =
        serviceTypeRepository.findByActiveTrueOrderByCode().stream()
            .map(ServiceTypeEntity::getCode)
            .toList();
    BOOKING_RESULTS.forEach(result -> {
      bookingCounter(UNKNOWN_SERVICE_TYPE, result);
      if (TRACKED_SERVICE_TYPE_RESULTS.contains(result)) {
        serviceTypeCodes.forEach(code -> bookingCounter(code, result));
      }
    });
  }

  private Counter bookingCounter(String serviceType, String result) {
    return meterRegistry.counter(
        MetricConstants.BOOKING_REQUESTS,
        MetricConstants.TAG_SERVICE_TYPE,
        serviceType,
        MetricConstants.TAG_RESULT,
        result);
  }

  private AppointmentResultDto bookWithRetry(BookAppointmentDto request, String idempotencyKey) {
    BookingDeadline deadline = BookingDeadline.startingNow(clock);
    for (int attempt = 1; attempt <= MAX_ATTEMPTS && !deadline.isExpired(); attempt++) {
      try {
        return transactionTemplate.execute(status -> bookOnce(request, idempotencyKey));
      } catch (DataAccessException ex) {
        handleConflict(ex, deadline, attempt < MAX_ATTEMPTS);
      } catch (CannotCreateTransactionException ex) {
        log.warn("Booking aborted, no database connection: {}", ex.getMessage());
        throw serviceBusy();
      }
    }
    if (deadline.isExpired()) {
      throw deadlineReached();
    }
    log.error(
        "Booking retry cap {} reached, dealershipId={}", MAX_ATTEMPTS, request.dealershipId());
    throw bookingNotCompleted();
  }

  private AppointmentResultDto bookOnce(BookAppointmentDto request, String idempotencyKey) {
    Optional<AppointmentResultDto> replay =
        idempotencyService.claim(idempotencyKey, canonicalRequest(request));
    if (replay.isPresent()) {
      return replay.get();
    }
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    BookingContext context = loadAndValidate(request, now);
    CandidateDto candidate = pickCandidate(context);
    AppointmentDto appointment = newAppointment(request, context, candidate, now);
    writeRepository.insert(appointmentMapper.toEntity(appointment));
    AppointmentResultDto result =
        AppointmentResultDto.builder().httpStatus(201).appointment(appointment).build();
    idempotencyService.complete(idempotencyKey, result);
    return result;
  }

  private BookingContext loadAndValidate(BookAppointmentDto request, Instant now) {
    DealershipEntity dealership = findDealership(request.dealershipId());
    VehicleEntity vehicle = findVehicle(request.vehicleId());
    ServiceTypeEntity serviceType = findServiceType(request.serviceTypeCode());
    ZoneId zone = ZoneId.of(dealership.getTimezone());
    Instant start = request.desiredStartTime().toInstant();
    Instant end = start.plus(Duration.ofMinutes(serviceType.getDurationMinutes()));
    requireBookableSlot(dealership, zone, start, serviceType.getDurationMinutes(), now);
    if (appointmentRepository.countVehicleOverlaps(vehicle.getId(), start, end) > 0) {
      throw vehicleAlreadyBooked();
    }
    return BookingContext.builder()
        .dealershipId(request.dealershipId())
        .serviceTypeCode(request.serviceTypeCode())
        .customerId(vehicle.getCustomerId())
        .start(start)
        .end(end)
        .day(TimeSlotUtils.localDay(start.atZone(zone).toLocalDate(), zone))
        .build();
  }

  private CandidateDto pickCandidate(BookingContext context) {
    List<UUID> freeBayIds =
        appointmentRepository.findFreeBayIds(
            context.dealershipId(), context.start(), context.end());
    List<UUID> freeTechnicianIds =
        appointmentRepository.findFreeTechnicianIds(
            context.dealershipId(), context.serviceTypeCode(), context.start(), context.end());
    if (freeBayIds.isEmpty() || freeTechnicianIds.isEmpty()) {
      throw new SchedulerException(ErrorCode.KUSS_NO_CAPACITY, ErrorMessageConstants.NO_CAPACITY);
    }
    List<AppointmentEntity> dayAppointments =
        appointmentRepository.findConfirmedOnDayForTechnicians(
            freeTechnicianIds, context.day().start(), context.day().end());
    List<TechnicianLoadDto> loads =
        CandidateSelectionUtils.loads(freeTechnicianIds, dayAppointments);
    return CandidateSelectionUtils.pick(freeBayIds, loads, ThreadLocalRandom.current());
  }

  private void handleConflict(
      DataAccessException ex, BookingDeadline deadline, boolean hasNextAttempt) {
    String constraint = SqlErrorUtils.constraintName(ex);
    String state = SqlErrorUtils.sqlState(ex);
    if (VEHICLE_OVERLAP_CONSTRAINT.equals(constraint)) {
      throw vehicleAlreadyBooked();
    }
    if (!RETRYABLE_STATES.contains(state)) {
      throw ex;
    }
    retryCounters.get(conflictType(constraint, state)).increment();
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
            .orElseThrow(AppointmentServiceImpl::deadlineReached);
    try {
      Thread.sleep(wait);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw bookingNotCompleted();
    }
  }

  private static String conflictType(String constraint, String state) {
    if (DEADLOCK_STATE.equals(state)) {
      return CONFLICT_DEADLOCK;
    }
    return BAY_OVERLAP_CONSTRAINT.equals(constraint) ? CONFLICT_BAY : CONFLICT_TECHNICIAN;
  }

  private static AppointmentDto newAppointment(
      BookAppointmentDto request, BookingContext context, CandidateDto candidate, Instant now) {
    return AppointmentDto.builder()
        .id(UUID.randomUUID())
        .dealershipId(context.dealershipId())
        .customerId(context.customerId())
        .vehicleId(request.vehicleId())
        .serviceBayId(candidate.serviceBayId())
        .technicianId(candidate.technicianId())
        .serviceTypeCode(context.serviceTypeCode())
        .startTime(context.start())
        .endTime(context.end())
        .status(AppointmentStatus.CONFIRMED)
        .createdAt(now)
        .build();
  }

  private static void requireBookableSlot(
      DealershipEntity dealership, ZoneId zone, Instant start, int durationMinutes, Instant now) {
    LocalDate date = start.atZone(zone).toLocalDate();
    if (!TimeSlotUtils.isWithinHorizon(date, zone, now)) {
      throw new SchedulerException(
          ErrorCode.KUSS_VALIDATION_FAILED, ErrorMessageConstants.DATE_OUT_OF_HORIZON);
    }
    List<Instant> starts =
        TimeSlotUtils.candidateStarts(
            date, zone, dealership.getOpenTime(), dealership.getCloseTime(), durationMinutes, now);
    if (!starts.contains(start)) {
      throw new SchedulerException(
          ErrorCode.KUSS_VALIDATION_FAILED, ErrorMessageConstants.SLOT_NOT_BOOKABLE);
    }
  }

  /**
   * Cancels a confirmed appointment that has not started yet. The row is locked for the update, so
   * a second canceller waits and then sees the cancelled state. Cancelling an already cancelled
   * appointment succeeds with its current state, even when its start is in the past.
   */
  @Override
  @Transactional(timeout = TRANSACTION_TIMEOUT_SECONDS)
  public AppointmentResultDto cancel(UUID appointmentId, String idempotencyKey) {
    Optional<AppointmentResultDto> replay =
        idempotencyService.claim(idempotencyKey, cancelRequest(appointmentId));
    if (replay.isPresent()) {
      return replay.get();
    }
    AppointmentEntity appointment =
        appointmentRepository.findForUpdate(appointmentId).orElseThrow(() -> appointmentNotFound());
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    AppointmentResultDto result =
        AppointmentResultDto.builder()
            .httpStatus(200)
            .appointment(cancelIfConfirmed(appointment, now))
            .build();
    idempotencyService.complete(idempotencyKey, result);
    return result;
  }

  @Override
  @Transactional(readOnly = true)
  public AppointmentDto getAppointment(UUID appointmentId) {
    return appointmentRepository
        .findById(appointmentId)
        .map(appointmentMapper::toDto)
        .orElseThrow(() -> appointmentNotFound());
  }

  /**
   * Lists the appointments that start on one local day of the dealership, cancelled ones included,
   * in start order. Only days from 365 days back to 90 days ahead are accepted.
   */
  @Override
  @Transactional(readOnly = true)
  public List<AppointmentDto> listForDay(UUID dealershipId, LocalDate date) {
    DealershipEntity dealership = findDealership(dealershipId);
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
    if (!TimeSlotUtils.isWithinHorizon(date, zone, now)) {
      throw new SchedulerException(
          ErrorCode.KUSS_VALIDATION_FAILED, ErrorMessageConstants.DATE_OUT_OF_HORIZON);
    }
  }

  private AppointmentDto cancelIfConfirmed(AppointmentEntity appointment, Instant now) {
    if (appointment.getStatus() == AppointmentStatus.CONFIRMED) {
      if (!appointment.getStartTime().isAfter(now)) {
        throw new SchedulerException(
            ErrorCode.KUSS_APPOINTMENT_ALREADY_STARTED,
            ErrorMessageConstants.APPOINTMENT_ALREADY_STARTED);
      }
      appointment.setStatus(AppointmentStatus.CANCELLED);
      appointment.setCancelledAt(now);
    }
    return appointmentMapper.toDto(appointment);
  }

  private static String cancelRequest(UUID appointmentId) {
    return "POST /api/v1/appointments/" + appointmentId + "/cancel";
  }

  private static SchedulerException appointmentNotFound() {
    return new SchedulerException(
        ErrorCode.KUSS_RESOURCE_NOT_FOUND, ErrorMessageConstants.APPOINTMENT_NOT_FOUND);
  }

  private static String canonicalRequest(BookAppointmentDto request) {
    return "POST /api/v1/appointments|"
        + request.dealershipId()
        + "|"
        + request.vehicleId()
        + "|"
        + request.serviceTypeCode()
        + "|"
        + request.desiredStartTime().toInstant();
  }

  private static SchedulerException bookingNotCompleted() {
    return new SchedulerException(
        ErrorCode.KUSS_INTERNAL_ERROR, ErrorMessageConstants.BOOKING_NOT_COMPLETED);
  }

  private static SchedulerException serviceBusy() {
    return new SchedulerException(ErrorCode.KUSS_SERVICE_BUSY, ErrorMessageConstants.SERVICE_BUSY);
  }

  private static SchedulerException deadlineReached() {
    log.warn("Booking deadline reached, giving up as service busy");
    return serviceBusy();
  }

  private static SchedulerException vehicleAlreadyBooked() {
    return new SchedulerException(
        ErrorCode.KUSS_VEHICLE_ALREADY_BOOKED, ErrorMessageConstants.VEHICLE_ALREADY_BOOKED);
  }

  private DealershipEntity findDealership(UUID dealershipId) {
    return dealershipRepository
        .findById(dealershipId)
        .orElseThrow(() ->
            new SchedulerException(
                ErrorCode.KUSS_RESOURCE_NOT_FOUND, ErrorMessageConstants.DEALERSHIP_NOT_FOUND));
  }

  private VehicleEntity findVehicle(UUID vehicleId) {
    return vehicleRepository
        .findById(vehicleId)
        .orElseThrow(() ->
            new SchedulerException(
                ErrorCode.KUSS_RESOURCE_NOT_FOUND, ErrorMessageConstants.VEHICLE_NOT_FOUND));
  }

  private ServiceTypeEntity findServiceType(String serviceTypeCode) {
    return serviceTypeRepository
        .findByCodeAndActiveTrue(serviceTypeCode)
        .orElseThrow(() ->
            new SchedulerException(
                ErrorCode.KUSS_RESOURCE_NOT_FOUND, ErrorMessageConstants.SERVICE_TYPE_NOT_FOUND));
  }

  @Builder
  private record BookingContext(
      UUID dealershipId,
      String serviceTypeCode,
      UUID customerId,
      Instant start,
      Instant end,
      TimeSlotUtils.Window day) {}
}
