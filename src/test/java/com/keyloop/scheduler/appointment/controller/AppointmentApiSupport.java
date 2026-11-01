package com.keyloop.scheduler.appointment.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.keyloop.scheduler.IntegrationTestSupport;
import com.keyloop.scheduler.SeedIds;
import com.keyloop.scheduler.appointment.dto.BookAppointmentDto;
import com.keyloop.scheduler.common.constants.ApiPathConstants;
import com.keyloop.scheduler.common.constants.HeaderConstants;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

abstract class AppointmentApiSupport extends IntegrationTestSupport {

  protected ResponseEntity<String> book(BookAppointmentDto request, String key) {
    return book(json(request), key);
  }

  protected ResponseEntity<String> book(String body, String key) {
    return postBooking(body, key);
  }

  protected ResponseEntity<String> cancel(UUID appointmentId, String key) {
    return http.post()
        .uri(ApiPathConstants.APPOINTMENTS + "/" + appointmentId + ApiPathConstants.CANCEL)
        .header(HeaderConstants.IDEMPOTENCY_KEY, key)
        .retrieve()
        .toEntity(String.class);
  }

  protected ResponseEntity<String> dayList(LocalDate date) {
    return dayList(SeedIds.LONDON_DEALERSHIP_ID, date);
  }

  protected ResponseEntity<String> dayList(UUID dealershipId, LocalDate date) {
    return get(ApiPathConstants.DEALERSHIPS + "/" + dealershipId + "/appointments?date=" + date);
  }

  protected void assertValidationFailed(ResponseEntity<String> response) {
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(code(response)).isEqualTo("KUSS_VALIDATION_FAILED");
  }

  protected String code(ResponseEntity<String> response) {
    return jsonMapper.readTree(response.getBody()).get("code").asString();
  }

  protected String detail(ResponseEntity<String> response) {
    return jsonMapper.readTree(response.getBody()).get("detail").asString();
  }

  protected String status(ResponseEntity<String> response) {
    return jsonMapper.readTree(response.getBody()).get("status").asString();
  }

  protected String id(ResponseEntity<String> response) {
    return jsonMapper.readTree(response.getBody()).get("id").asString();
  }

  protected String json(BookAppointmentDto request) {
    return jsonMapper.writeValueAsString(request);
  }

  protected BookAppointmentDto request(
      UUID vehicleId, String serviceTypeCode, OffsetDateTime start) {
    return request(SeedIds.LONDON_DEALERSHIP_ID, vehicleId, serviceTypeCode, start);
  }

  protected BookAppointmentDto request(
      UUID dealershipId, UUID vehicleId, String serviceTypeCode, OffsetDateTime start) {
    return BookAppointmentDto.builder()
        .dealershipId(dealershipId)
        .vehicleId(vehicleId)
        .serviceTypeCode(serviceTypeCode)
        .desiredStartTime(start)
        .build();
  }

  protected int countAppointments() {
    return jdbcClient.sql("SELECT count(*) FROM appointment").query(Integer.class).single();
  }
}
