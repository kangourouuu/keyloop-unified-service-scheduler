package com.keyloop.scheduler.availability;

import com.keyloop.scheduler.appointment.AppointmentRepository;
import com.keyloop.scheduler.catalog.DealershipEntity;
import com.keyloop.scheduler.catalog.DealershipRepository;
import com.keyloop.scheduler.catalog.ServiceBayRepository;
import com.keyloop.scheduler.catalog.ServiceTypeEntity;
import com.keyloop.scheduler.catalog.ServiceTypeRepository;
import com.keyloop.scheduler.catalog.TechnicianRepository;
import com.keyloop.scheduler.common.constants.ErrorMessageConstants;
import com.keyloop.scheduler.common.error.ErrorCode;
import com.keyloop.scheduler.common.error.SchedulerException;
import com.keyloop.scheduler.common.util.TimeSlotUtils;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class AvailabilityServiceImpl implements AvailabilityService {

  private final Clock clock;
  private final DealershipRepository dealershipRepository;
  private final ServiceTypeRepository serviceTypeRepository;
  private final ServiceBayRepository serviceBayRepository;
  private final TechnicianRepository technicianRepository;
  private final AppointmentRepository appointmentRepository;
  private final AvailabilityMapper availabilityMapper;

  /**
   * Returns the bookable start times of one local day with the capacity left at each. Capacity is
   * the number of free bays that also have a free qualified technician for the whole service
   * duration. Starts already in the past are dropped, and busy resources outside this dealership or
   * outside the qualified set never reduce capacity.
   */
  @Override
  @Transactional(readOnly = true)
  public AvailabilityDto getAvailability(
      UUID dealershipId, LocalDate date, String serviceTypeCode) {
    Instant now = clock.instant();
    DealershipEntity dealership = findDealership(dealershipId);
    ServiceTypeEntity serviceType = findServiceType(serviceTypeCode);
    ZoneId zone = ZoneId.of(dealership.getTimezone());
    requireWithinHorizon(date, zone, now);
    int durationMinutes = serviceType.getDurationMinutes();
    List<Instant> starts =
        TimeSlotUtils.candidateStarts(
            date, zone, dealership.getOpenTime(), dealership.getCloseTime(), durationMinutes, now);
    return AvailabilityDto.builder()
        .dealershipId(dealershipId)
        .date(date)
        .serviceTypeCode(serviceTypeCode)
        .timezone(dealership.getTimezone())
        .slots(sweep(dealership, date, zone, starts, durationMinutes, serviceTypeCode))
        .build();
  }

  private static void requireWithinHorizon(LocalDate date, ZoneId zone, Instant now) {
    if (!TimeSlotUtils.isWithinHorizon(date, zone, now)) {
      throw new SchedulerException(
          ErrorCode.KUSS_VALIDATION_FAILED, ErrorMessageConstants.DATE_OUT_OF_HORIZON);
    }
  }

  private List<SlotDto> sweep(
      DealershipEntity dealership,
      LocalDate date,
      ZoneId zone,
      List<Instant> starts,
      int durationMinutes,
      String serviceTypeCode) {
    UUID dealershipId = dealership.getId();
    Set<UUID> bayIds = Set.copyOf(serviceBayRepository.findActiveIds(dealershipId));
    Set<UUID> technicianIds =
        Set.copyOf(technicianRepository.findQualifiedActiveIds(dealershipId, serviceTypeCode));
    TimeSlotUtils.Window window =
        TimeSlotUtils.openWindow(date, zone, dealership.getOpenTime(), dealership.getCloseTime());
    List<BusyIntervalDto> busy =
        appointmentRepository
            .findConfirmedOverlapping(dealershipId, window.start(), window.end())
            .stream()
            .map(availabilityMapper::toBusyInterval)
            .toList();
    return AvailabilitySweepUtils.sweep(starts, durationMinutes, zone, bayIds, technicianIds, busy);
  }

  private DealershipEntity findDealership(UUID dealershipId) {
    return dealershipRepository
        .findById(dealershipId)
        .orElseThrow(() ->
            new SchedulerException(
                ErrorCode.KUSS_RESOURCE_NOT_FOUND, ErrorMessageConstants.DEALERSHIP_NOT_FOUND));
  }

  private ServiceTypeEntity findServiceType(String serviceTypeCode) {
    return serviceTypeRepository
        .findByCodeAndActiveTrue(serviceTypeCode)
        .orElseThrow(() ->
            new SchedulerException(
                ErrorCode.KUSS_RESOURCE_NOT_FOUND, ErrorMessageConstants.SERVICE_TYPE_NOT_FOUND));
  }
}
