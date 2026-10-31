package com.keyloop.scheduler.appointment.controller;

import com.keyloop.scheduler.appointment.dto.AppointmentDto;
import com.keyloop.scheduler.appointment.dto.AppointmentResultDto;
import com.keyloop.scheduler.appointment.dto.BookAppointmentDto;
import com.keyloop.scheduler.appointment.service.AppointmentService;
import com.keyloop.scheduler.common.constants.ApiPathConstants;
import com.keyloop.scheduler.common.constants.HeaderConstants;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
class AppointmentController {

  private static final String IDEMPOTENCY_KEY_PATTERN = "[A-Za-z0-9_-]{1,128}";

  private final AppointmentService appointmentService;

  @PostMapping(ApiPathConstants.APPOINTMENTS)
  ResponseEntity<AppointmentDto> book(
      @RequestHeader(HeaderConstants.IDEMPOTENCY_KEY) @Pattern(regexp = IDEMPOTENCY_KEY_PATTERN)
          String idempotencyKey,
      @Valid @RequestBody BookAppointmentDto request) {
    AppointmentResultDto result = appointmentService.book(request, idempotencyKey);
    return ResponseEntity.status(result.httpStatus()).body(result.appointment());
  }

  @GetMapping(ApiPathConstants.APPOINTMENTS + "/{appointmentId}")
  AppointmentDto getAppointment(@PathVariable UUID appointmentId) {
    return appointmentService.getAppointment(appointmentId);
  }

  @PostMapping(ApiPathConstants.APPOINTMENTS + "/{appointmentId}/cancel")
  ResponseEntity<AppointmentDto> cancel(
      @PathVariable UUID appointmentId,
      @RequestHeader(HeaderConstants.IDEMPOTENCY_KEY) @Pattern(regexp = IDEMPOTENCY_KEY_PATTERN)
          String idempotencyKey) {
    AppointmentResultDto result = appointmentService.cancel(appointmentId, idempotencyKey);
    return ResponseEntity.status(result.httpStatus()).body(result.appointment());
  }

  @GetMapping("/api/v1/dealerships/{dealershipId}/appointments")
  List<AppointmentDto> listForDay(
      @PathVariable UUID dealershipId,
      @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
    return appointmentService.listForDay(dealershipId, date);
  }
}
