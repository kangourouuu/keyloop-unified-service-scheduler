package com.keyloop.scheduler.availability.controller;

import com.keyloop.scheduler.availability.dto.AvailabilityDto;
import com.keyloop.scheduler.availability.service.AvailabilityService;
import com.keyloop.scheduler.common.constants.ApiPathConstants;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
@RequestMapping(ApiPathConstants.DEALERSHIPS + "/{dealershipId}/availability")
class AvailabilityController {

  private final AvailabilityService availabilityService;

  @GetMapping
  AvailabilityDto getAvailability(
      @PathVariable UUID dealershipId,
      @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @RequestParam("serviceType") @NotBlank @Size(max = 40) String serviceType) {
    return availabilityService.getAvailability(dealershipId, date, serviceType);
  }
}
