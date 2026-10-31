package com.keyloop.scheduler.availability.service;

import com.keyloop.scheduler.availability.dto.AvailabilityDto;
import java.time.LocalDate;
import java.util.UUID;

public interface AvailabilityService {

  AvailabilityDto getAvailability(UUID dealershipId, LocalDate date, String serviceTypeCode);
}
