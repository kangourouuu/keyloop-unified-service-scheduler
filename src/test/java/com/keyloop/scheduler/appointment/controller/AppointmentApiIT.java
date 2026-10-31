package com.keyloop.scheduler.appointment.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.keyloop.scheduler.IntegrationTestSupport;
import com.keyloop.scheduler.SeedIds;
import com.keyloop.scheduler.appointment.dto.BookAppointmentDto;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AppointmentApiIT extends IntegrationTestSupport {

  private static final ZoneId LONDON = ZoneId.of("Europe/London");
  private static final String KEY_HEADER = "Idempotency-Key";

  @Autowired private Clock clock;

  @Autowired private JsonMapper jsonMapper;

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
  }

  @Test
  void should_return_400_idempotency_key_missing_on_book_and_cancel() {
    ResponseEntity<String> booking =
        http.post()
            .uri("/api/v1/appointments")
            .contentType(MediaType.APPLICATION_JSON)
            .body(json(request(SeedIds.vehicle(1), "OIL_CHANGE", dayAt(10, 0))))
            .retrieve()
            .toEntity(String.class);
    ResponseEntity<String> cancel =
        http.post()
            .uri("/api/v1/appointments/" + UUID.randomUUID() + "/cancel")
            .retrieve()
            .toEntity(String.class);

    assertThat(booking.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(code(booking)).isEqualTo("KUSS_IDEMPOTENCY_KEY_MISSING");
    assertThat(code(cancel)).isEqualTo("KUSS_IDEMPOTENCY_KEY_MISSING");
  }

  @Test
  void should_return_404_for_unknown_dealership_vehicle_service_type_and_appointment() {
    assertThat(
            book(
                    request(UUID.randomUUID(), SeedIds.vehicle(1), "OIL_CHANGE", dayAt(10, 0)),
                    "k-no-dealer")
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(
            book(
                    request(
                        SeedIds.LONDON_DEALERSHIP_ID,
                        UUID.randomUUID(),
                        "OIL_CHANGE",
                        dayAt(10, 0)),
                    "k-no-vehicle-id")
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(book(request(SeedIds.vehicle(1), "NOPE", dayAt(10, 0)), "k-no-type").getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(get("/api/v1/appointments/" + UUID.randomUUID()).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(cancel(UUID.randomUUID(), "k-cancel-missing").getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(
            get("/api/v1/dealerships/"
                    + UUID.randomUUID()
                    + "/appointments?date="
                    + sevenDaysAhead())
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void should_return_409_vehicle_already_booked_for_an_overlapping_service() {
    assertThat(
            book(request(SeedIds.vehicle(1), "OIL_CHANGE", dayAt(15, 0)), "k-first")
                .getStatusCode())
        .isEqualTo(HttpStatus.CREATED);

    ResponseEntity<String> overlapping =
        book(request(SeedIds.vehicle(1), "BRAKE_REPAIR", dayAt(14, 30)), "k-overlap");

    assertThat(overlapping.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(code(overlapping)).isEqualTo("KUSS_VEHICLE_ALREADY_BOOKED");
  }

  @Test
  void should_return_409_no_capacity_when_the_fourth_oil_change_hits_a_full_slot() {
    for (int vehicle = 1; vehicle <= 3; vehicle++) {
      assertThat(
              book(
                      request(SeedIds.vehicle(vehicle), "OIL_CHANGE", dayAt(10, 0)),
                      "k-fill-" + vehicle)
                  .getStatusCode())
          .isEqualTo(HttpStatus.CREATED);
    }

    ResponseEntity<String> fourth =
        book(request(SeedIds.vehicle(4), "OIL_CHANGE", dayAt(10, 0)), "k-fourth");

    assertThat(fourth.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(code(fourth)).isEqualTo("KUSS_NO_CAPACITY");
  }

  @Test
  void should_return_409_already_started_when_cancelling_a_past_appointment() {
    UUID started =
        insertAppointment(
            SeedIds.LONDON_BAY_1_ID,
            SeedIds.LONDON_TECH_1_ID,
            SeedIds.VEHICLE_1_ID,
            Instant.now(clock).minusSeconds(3600),
            Instant.now(clock).plusSeconds(1800),
            "CONFIRMED");

    ResponseEntity<String> response = cancel(started, "k-started");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(code(response)).isEqualTo("KUSS_APPOINTMENT_ALREADY_STARTED");
  }

  @Test
  void should_return_422_when_the_same_key_carries_another_payload() {
    book(request(SeedIds.vehicle(1), "OIL_CHANGE", dayAt(10, 0)), "k-payload");

    ResponseEntity<String> response =
        book(request(SeedIds.vehicle(2), "OIL_CHANGE", dayAt(10, 0)), "k-payload");

    assertThat(response.getStatusCode().value()).isEqualTo(422);
    assertThat(code(response)).isEqualTo("KUSS_IDEMPOTENCY_KEY_MISMATCH");
  }

  @Test
  void should_replay_book_with_reformatted_json_as_the_same_appointment() throws Exception {
    String compact = json(request(SeedIds.vehicle(1), "OIL_CHANGE", dayAt(10, 0)));
    String pretty =
        "{\n  \"serviceTypeCode\": \"OIL_CHANGE\",\n  \"desiredStartTime\": \""
            + dayAt(10, 0)
            + "\",\n  \"vehicleId\": \""
            + SeedIds.vehicle(1)
            + "\",\n  \"dealershipId\": \""
            + SeedIds.LONDON_DEALERSHIP_ID
            + "\"\n}";

    ResponseEntity<String> first = book(compact, "k-replay-json");
    ResponseEntity<String> second = book(pretty, "k-replay-json");

    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(id(second)).isEqualTo(id(first));
    assertThat(countAppointments()).isEqualTo(1);
  }

  @Test
  void should_replay_cancel_and_report_cancelled_for_a_new_key_on_a_cancelled_appointment() {
    String id = id(book(request(SeedIds.vehicle(1), "OIL_CHANGE", dayAt(10, 0)), "k-book-cancel"));

    ResponseEntity<String> cancelled = cancel(UUID.fromString(id), "k-cancel");
    ResponseEntity<String> replayed = cancel(UUID.fromString(id), "k-cancel");
    ResponseEntity<String> again = cancel(UUID.fromString(id), "k-cancel-again");

    assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(replayed.getBody()).isEqualTo(cancelled.getBody());
    assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(status(again)).isEqualTo("CANCELLED");
  }

  @Test
  void should_return_exactly_3_created_and_7_no_capacity_for_10_parallel_http_requests()
      throws Exception {
    List<String> outcomes = new ArrayList<>();
    CountDownLatch startGate = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newFixedThreadPool(10)) {
      List<Future<String>> futures = new ArrayList<>();
      for (int vehicle = 1; vehicle <= 10; vehicle++) {
        String vehicleId = SeedIds.vehicle(vehicle).toString();
        String key = "k-burst-" + vehicle;
        futures.add(
            executor.submit(() -> {
              startGate.await();
              ResponseEntity<String> response =
                  book(request(UUID.fromString(vehicleId), "OIL_CHANGE", dayAt(10, 0)), key);
              return response.getStatusCode().value() == 201 ? "201" : code(response);
            }));
      }
      startGate.countDown();
      for (Future<String> future : futures) {
        outcomes.add(future.get(30, TimeUnit.SECONDS));
      }
    }

    assertThat(outcomes).filteredOn("201"::equals).hasSize(3);
    assertThat(outcomes).filteredOn("KUSS_NO_CAPACITY"::equals).hasSize(7);
  }

  @Test
  void should_free_capacity_on_cancel_so_a_new_vehicle_can_book_the_slot() {
    String first = id(book(request(SeedIds.vehicle(1), "OIL_CHANGE", dayAt(10, 0)), "k-c1"));
    book(request(SeedIds.vehicle(2), "OIL_CHANGE", dayAt(10, 0)), "k-c2");
    book(request(SeedIds.vehicle(3), "OIL_CHANGE", dayAt(10, 0)), "k-c3");
    assertThat(cancel(UUID.fromString(first), "k-c-cancel").getStatusCode())
        .isEqualTo(HttpStatus.OK);

    assertThat(
            book(request(SeedIds.vehicle(4), "OIL_CHANGE", dayAt(10, 0)), "k-c4").getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
  }

  @Test
  void should_read_back_an_appointment_and_list_the_day_in_start_order() {
    String late = id(book(request(SeedIds.vehicle(2), "OIL_CHANGE", dayAt(11, 0)), "k-late"));
    String early = id(book(request(SeedIds.vehicle(1), "OIL_CHANGE", dayAt(10, 0)), "k-early"));
    cancel(UUID.fromString(early), "k-early-cancel");

    ResponseEntity<String> single = get("/api/v1/appointments/" + late);
    JsonNode day =
        jsonMapper.readTree(
            get("/api/v1/dealerships/"
                    + SeedIds.LONDON_DEALERSHIP_ID
                    + "/appointments?date="
                    + sevenDaysAhead())
                .getBody());

    assertThat(single.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(id(single)).isEqualTo(late);
    assertThat(day.size()).isEqualTo(2);
    assertThat(day.get(0).get("id").asString()).isEqualTo(early);
    assertThat(day.get(0).get("status").asString()).isEqualTo("CANCELLED");
    assertThat(day.get(1).get("id").asString()).isEqualTo(late);
  }

  @Test
  void should_publish_the_appointment_paths_in_openapi() {
    ResponseEntity<String> docs = get("/v3/api-docs");

    assertThat(docs.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(docs.getBody()).contains("/api/v1/appointments");
  }

  private ResponseEntity<String> book(BookAppointmentDto request, String key) {
    return book(json(request), key);
  }

  private ResponseEntity<String> book(String body, String key) {
    return http.post()
        .uri("/api/v1/appointments")
        .header(KEY_HEADER, key)
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .retrieve()
        .toEntity(String.class);
  }

  private ResponseEntity<String> cancel(UUID appointmentId, String key) {
    return http.post()
        .uri("/api/v1/appointments/" + appointmentId + "/cancel")
        .header(KEY_HEADER, key)
        .retrieve()
        .toEntity(String.class);
  }

  private ResponseEntity<String> get(String uri) {
    return http.get().uri(uri).retrieve().toEntity(String.class);
  }

  private void assertValidationFailed(ResponseEntity<String> response) {
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(code(response)).isEqualTo("KUSS_VALIDATION_FAILED");
  }

  private String code(ResponseEntity<String> response) {
    return jsonMapper.readTree(response.getBody()).get("code").asString();
  }

  private String status(ResponseEntity<String> response) {
    return jsonMapper.readTree(response.getBody()).get("status").asString();
  }

  private String id(ResponseEntity<String> response) {
    return jsonMapper.readTree(response.getBody()).get("id").asString();
  }

  private String json(BookAppointmentDto request) {
    return jsonMapper.writeValueAsString(request);
  }

  private BookAppointmentDto request(UUID vehicleId, String serviceTypeCode, OffsetDateTime start) {
    return request(SeedIds.LONDON_DEALERSHIP_ID, vehicleId, serviceTypeCode, start);
  }

  private BookAppointmentDto request(
      UUID dealershipId, UUID vehicleId, String serviceTypeCode, OffsetDateTime start) {
    return BookAppointmentDto.builder()
        .dealershipId(dealershipId)
        .vehicleId(vehicleId)
        .serviceTypeCode(serviceTypeCode)
        .desiredStartTime(start)
        .build();
  }

  private int countAppointments() {
    return jdbcClient.sql("SELECT count(*) FROM appointment").query(Integer.class).single();
  }

  private LocalDate sevenDaysAhead() {
    return LocalDate.now(clock).plusDays(7);
  }

  private OffsetDateTime dayAt(int hour, int minute) {
    return sevenDaysAhead().atTime(hour, minute).atZone(LONDON).toOffsetDateTime();
  }
}
