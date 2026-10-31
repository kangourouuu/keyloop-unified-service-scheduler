package com.keyloop.scheduler.availability;

import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/dealerships/{dealershipId}/availability")
class AvailabilityController {

  private final AvailabilityService availabilityService;

  @GetMapping
  AvailabilityDto getAvailability(
      @PathVariable UUID dealershipId,
      @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @RequestParam("serviceType") String serviceType) {
    return availabilityService.getAvailability(dealershipId, date, serviceType);
  }
}
