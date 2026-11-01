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
import com.keyloop.scheduler.appointment.service.IdempotencyService;
import com.keyloop.scheduler.appointment.util.BookingErrorUtils;
import com.keyloop.scheduler.appointment.util.CandidateSelectionUtils;
import com.keyloop.scheduler.catalog.entity.DealershipEntity;
import com.keyloop.scheduler.catalog.entity.ServiceTypeEntity;
import com.keyloop.scheduler.catalog.entity.VehicleEntity;
import com.keyloop.scheduler.catalog.service.CatalogService;
import com.keyloop.scheduler.common.constants.ApiPathConstants;
import com.keyloop.scheduler.common.constants.ErrorMessageConstants;
import com.keyloop.scheduler.common.error.ErrorCode;
import com.keyloop.scheduler.common.error.SchedulerException;
import com.keyloop.scheduler.common.util.TimeSlotUtils;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class BookingAttemptHelper {

  private final Clock clock;
  private final CatalogService catalogService;
  private final AppointmentRepository appointmentRepository;
  private final AppointmentMapper appointmentMapper;
  private final IdempotencyService idempotencyService;

  /**
   * Runs one booking attempt inside the caller's transaction: claims the idempotency key, replays
   * the stored result when the key was already answered, validates the slot and the vehicle, picks
   * a free bay and qualified technician pair, inserts the appointment and stores the response. A
   * conflict between concurrent bookings surfaces as a database exclusion error from the insert.
   */
  AppointmentResultDto bookOnce(BookAppointmentDto request, String idempotencyKey) {
    Optional<AppointmentResultDto> replay =
        idempotencyService.claim(idempotencyKey, canonicalRequest(request));
    if (replay.isPresent()) {
      return replay.get();
    }
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    BookingContext context = loadAndValidate(request, now);
    CandidateDto candidate = pickCandidate(context);
    AppointmentDto appointment = newAppointment(request, context, candidate, now);
    appointmentRepository.saveAndFlush(appointmentMapper.toEntity(appointment));
    AppointmentResultDto result =
        AppointmentResultDto.builder().httpStatus(201).appointment(appointment).build();
    idempotencyService.complete(idempotencyKey, result);
    return result;
  }

  private BookingContext loadAndValidate(BookAppointmentDto request, Instant now) {
    DealershipEntity dealership = catalogService.requireDealership(request.dealershipId());
    VehicleEntity vehicle = catalogService.requireVehicle(request.vehicleId());
    ServiceTypeEntity serviceType = catalogService.requireServiceType(request.serviceTypeCode());
    ZoneId zone = ZoneId.of(dealership.getTimezone());
    Instant start = request.desiredStartTime().toInstant();
    Instant end = start.plus(Duration.ofMinutes(serviceType.getDurationMinutes()));
    requireBookableSlot(dealership, zone, start, serviceType.getDurationMinutes(), now);
    if (appointmentRepository.countVehicleOverlaps(vehicle.getId(), start, end) > 0) {
      throw BookingErrorUtils.vehicleAlreadyBooked();
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
    List<UUID> freeServiceBayIds =
        appointmentRepository.findFreeBayIds(
            context.dealershipId(), context.start(), context.end());
    List<UUID> freeQualifiedTechnicianIds =
        appointmentRepository.findFreeTechnicianIds(
            context.dealershipId(), context.serviceTypeCode(), context.start(), context.end());
    if (freeServiceBayIds.isEmpty() || freeQualifiedTechnicianIds.isEmpty()) {
      throw new SchedulerException(ErrorCode.KUSS_NO_CAPACITY, ErrorMessageConstants.NO_CAPACITY);
    }
    List<AppointmentEntity> confirmedAppointmentDays =
        appointmentRepository.findConfirmedOnDayForTechnicians(
            freeQualifiedTechnicianIds, context.day().start(), context.day().end());
    List<TechnicianLoadDto> loads =
        CandidateSelectionUtils.loads(freeQualifiedTechnicianIds, confirmedAppointmentDays);
    return CandidateSelectionUtils.pick(freeServiceBayIds, loads, ThreadLocalRandom.current());
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
    TimeSlotUtils.requireWithinHorizon(date, zone, now);
    if (!TimeSlotUtils.isBookableStart(
        start,
        date,
        zone,
        dealership.getOpenTime(),
        dealership.getCloseTime(),
        durationMinutes,
        now)) {
      throw new SchedulerException(
          ErrorCode.KUSS_VALIDATION_FAILED, ErrorMessageConstants.SLOT_NOT_BOOKABLE);
    }
  }

  private static String canonicalRequest(BookAppointmentDto request) {
    return HttpMethod.POST.name()
        + " "
        + ApiPathConstants.APPOINTMENTS
        + "|"
        + request.dealershipId()
        + "|"
        + request.vehicleId()
        + "|"
        + request.serviceTypeCode()
        + "|"
        + request.desiredStartTime().toInstant();
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
