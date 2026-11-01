package com.keyloop.scheduler.appointment.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.keyloop.scheduler.SeedIds;
import com.keyloop.scheduler.common.constants.ApiPathConstants;
import com.keyloop.scheduler.common.constants.ErrorMessageConstants;
import com.keyloop.scheduler.common.constants.HeaderConstants;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

class AppointmentValidationApiIT extends AppointmentApiSupport {

  @Test
  void should_return_400_validation_failed_for_bad_booking_input() {
    OffsetDateTime offGrid = dayAt(9, 15);
    OffsetDateTime yesterday =
        LocalDate.now(clock).minusDays(1).atTime(10, 0).atZone(LONDON).toOffsetDateTime();
    OffsetDateTime beyondHorizon =
        LocalDate.now(clock).plusDays(91).atTime(10, 0).atZone(LONDON).toOffsetDateTime();

    assertValidationFailed(book(request(SeedIds.vehicle(1), "OIL_CHANGE", offGrid), "k-off-grid"));
    assertValidationFailed(book(request(SeedIds.vehicle(1), "OIL_CHANGE", yesterday), "k-past"));
    assertValidationFailed(
        book(request(SeedIds.vehicle(1), "BRAKE_REPAIR", dayAt(17, 0)), "k-closing"));
    assertValidationFailed(
        book(request(SeedIds.vehicle(1), "OIL_CHANGE", beyondHorizon), "k-horizon"));
    assertValidationFailed(
        book(
            "{\"dealershipId\":\""
                + SeedIds.LONDON_DEALERSHIP_ID
                + "\",\"serviceTypeCode\":\"OIL_CHANGE\",\"desiredStartTime\":\""
                + dayAt(10, 0).toLocalDateTime()
                + "\"}",
            "k-no-offset"));
    assertValidationFailed(
        book(
            "{\"dealershipId\":\""
                + SeedIds.LONDON_DEALERSHIP_ID
                + "\",\"serviceTypeCode\":\"OIL_CHANGE\",\"desiredStartTime\":\""
                + dayAt(10, 0)
                + "\"}",
            "k-no-vehicle"));
    assertValidationFailed(
        book(request(SeedIds.vehicle(1), "OIL_CHANGE", dayAt(10, 0)), "a".repeat(129)));
    assertValidationFailed(book(request(SeedIds.vehicle(1), "OIL_CHANGE", dayAt(10, 0)), "a b"));
    assertValidationFailed(
        book(
            bookingBody(null, SeedIds.vehicle(1), "OIL_CHANGE", dayAt(10, 0).toString()),
            "k-no-dealership"));
    assertValidationFailed(
        book(request(SeedIds.vehicle(1), " ", dayAt(10, 0)), "k-blank-service-type"));
    assertValidationFailed(
        book(request(SeedIds.vehicle(1), "S".repeat(41), dayAt(10, 0)), "k-long-service-type"));
    assertValidationFailed(
        book(
            bookingBody(SeedIds.LONDON_DEALERSHIP_ID, SeedIds.vehicle(1), "OIL_CHANGE", null),
            "k-no-start"));
  }

  private static String bookingBody(
      UUID dealershipId, UUID vehicleId, String serviceTypeCode, String desiredStartTime) {
    return "{\"dealershipId\":%s,\"vehicleId\":\"%s\",\"serviceTypeCode\":\"%s\",\"desiredStartTime\":%s}"
        .formatted(
            dealershipId == null ? "null" : "\"" + dealershipId + "\"",
            vehicleId,
            serviceTypeCode,
            desiredStartTime == null ? "null" : "\"" + desiredStartTime + "\"");
  }

  @Test
  void should_name_each_invalid_field_once_in_the_400_detail() {
    ResponseEntity<String> empty = book("{}", "k-empty");
    ResponseEntity<String> longCode =
        book(request(SeedIds.vehicle(1), "S".repeat(41), dayAt(10, 0)), "k-long-code");
    ResponseEntity<String> badKey =
        book(request(SeedIds.vehicle(1), "OIL_CHANGE", dayAt(10, 0)), "a b");

    assertThat(detail(empty))
        .contains("dealershipId: ", "vehicleId: ", "serviceTypeCode: ", "desiredStartTime: ")
        .doesNotContain("request:");
    assertThat(detail(longCode)).startsWith("serviceTypeCode: ").doesNotContain("request:");
    assertThat(detail(badKey)).startsWith("idempotencyKey: ");
    assertThat(detail(empty).split(", ")).doesNotHaveDuplicates();
  }

  @Test
  void should_return_400_idempotency_key_missing_on_book_and_cancel() {
    ResponseEntity<String> booking =
        http.post()
            .uri(ApiPathConstants.APPOINTMENTS)
            .contentType(MediaType.APPLICATION_JSON)
            .body(json(request(SeedIds.vehicle(1), "OIL_CHANGE", dayAt(10, 0))))
            .retrieve()
            .toEntity(String.class);
    ResponseEntity<String> cancel =
        http.post()
            .uri(ApiPathConstants.APPOINTMENTS + "/" + UUID.randomUUID() + ApiPathConstants.CANCEL)
            .retrieve()
            .toEntity(String.class);

    assertThat(booking.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(code(booking)).isEqualTo("KUSS_IDEMPOTENCY_KEY_MISSING");
    assertThat(code(cancel)).isEqualTo("KUSS_IDEMPOTENCY_KEY_MISSING");
  }

  @Test
  void should_return_idempotency_key_missing_detail_even_when_body_is_invalid() {
    ResponseEntity<String> booking =
        http.post()
            .uri(ApiPathConstants.APPOINTMENTS)
            .contentType(MediaType.APPLICATION_JSON)
            .body("{}")
            .retrieve()
            .toEntity(String.class);
    ResponseEntity<String> cancel =
        http.post()
            .uri(ApiPathConstants.APPOINTMENTS + "/" + UUID.randomUUID() + ApiPathConstants.CANCEL)
            .retrieve()
            .toEntity(String.class);

    assertThat(booking.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(code(booking)).isEqualTo("KUSS_IDEMPOTENCY_KEY_MISSING");
    assertThat(code(cancel)).isEqualTo("KUSS_IDEMPOTENCY_KEY_MISSING");
    assertThat(detail(booking)).isEqualTo(ErrorMessageConstants.IDEMPOTENCY_KEY_MISSING);
    assertThat(detail(cancel)).isEqualTo(ErrorMessageConstants.IDEMPOTENCY_KEY_MISSING);
  }

  @Test
  void should_document_idempotency_key_header_as_optional_in_openapi() {
    ResponseEntity<String> docs = http.get().uri("/v3/api-docs").retrieve().toEntity(String.class);

    JsonNode paths = jsonMapper.readTree(docs.getBody()).get("paths");
    for (String path :
        List.of(
            ApiPathConstants.APPOINTMENTS,
            ApiPathConstants.APPOINTMENTS + "/{appointmentId}" + ApiPathConstants.CANCEL)) {
      for (JsonNode parameter : paths.get(path).get("post").get("parameters")) {
        if (HeaderConstants.IDEMPOTENCY_KEY.equals(parameter.get("name").asString())) {
          assertThat(parameter.path("required").asBoolean()).isFalse();
        }
      }
    }
  }
}
