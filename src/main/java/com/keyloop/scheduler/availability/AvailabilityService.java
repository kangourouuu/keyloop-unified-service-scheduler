package com.keyloop.scheduler.availability;

import java.time.LocalDate;
import java.util.UUID;

public interface AvailabilityService {

  AvailabilityDto getAvailability(UUID dealershipId, LocalDate date, String serviceTypeCode);
}
