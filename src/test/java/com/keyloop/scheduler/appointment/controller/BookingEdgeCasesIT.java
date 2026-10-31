package com.keyloop.scheduler.appointment.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.keyloop.scheduler.IntegrationTestSupport;
import com.keyloop.scheduler.SeedIds;
import com.keyloop.scheduler.appointment.dto.BookAppointmentDto;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;

class BookingEdgeCasesIT extends IntegrationTestSupport {

  private static final String KEY_HEADER = "Idempotency-Key";

  @Autowired private JsonMapper jsonMapper;

  @Test
  void should_book_the_last_slots_that_end_exactly_at_closing_time() {
    ResponseEntity<String> brake =
        book(request(SeedIds.vehicle(1), "BRAKE_REPAIR", dayAt(16, 30)), "k-close-brake");
    ResponseEntity<String> oil =
        book(request(SeedIds.vehicle(2), "OIL_CHANGE", dayAt(17, 30)), "k-close-oil");

    assertThat(brake.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(oil.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(Instant.parse(field(brake, "endTime"))).isEqualTo(dayAt(18, 0).toInstant());
    assertThat(Instant.parse(field(oil, "endTime"))).isEqualTo(dayAt(18, 0).toInstant());
  }

  @Test
  void should_book_back_to_back_services_for_the_same_vehicle() {
    ResponseEntity<String> first =
        book(request(SeedIds.vehicle(1), "OIL_CHANGE", dayAt(10, 0)), "k-b2b-1");
    ResponseEntity<String> second =
        book(request(SeedIds.vehicle(1), "OIL_CHANGE", dayAt(10, 30)), "k-b2b-2");

    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(confirmedCount()).isEqualTo(2);
  }

  @Test
  void should_accept_an_idempotency_key_of_128_characters() {
    ResponseEntity<String> response =
        book(request(SeedIds.vehicle(1), "OIL_CHANGE", dayAt(10, 0)), "k".repeat(128));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
  }

  @Test
  void should_reject_a_cancel_key_reused_for_another_appointment_as_422() {
    String first =
        field(book(request(SeedIds.vehicle(1), "OIL_CHANGE", dayAt(10, 0)), "k-a"), "id");
    String second =
        field(book(request(SeedIds.vehicle(2), "OIL_CHANGE", dayAt(11, 0)), "k-b"), "id");
    cancel(UUID.fromString(first), "k-shared");

    ResponseEntity<String> reused = cancel(UUID.fromString(second), "k-shared");

    assertThat(reused.getStatusCode().value()).isEqualTo(422);
    assertThat(field(reused, "code")).isEqualTo("KUSS_IDEMPOTENCY_KEY_MISMATCH");
    assertThat(statusOf(second)).isEqualTo("CONFIRMED");
  }

  @Test
  void should_reject_a_booking_key_reused_to_cancel_as_422() {
    String id =
        field(book(request(SeedIds.vehicle(1), "OIL_CHANGE", dayAt(10, 0)), "k-book"), "id");

    ResponseEntity<String> reused = cancel(UUID.fromString(id), "k-book");

    assertThat(reused.getStatusCode().value()).isEqualTo(422);
    assertThat(field(reused, "code")).isEqualTo("KUSS_IDEMPOTENCY_KEY_MISMATCH");
    assertThat(statusOf(id)).isEqualTo("CONFIRMED");
  }

  @Test
  void should_cancel_once_and_answer_200_when_ten_requests_cancel_with_different_keys()
      throws Exception {
    UUID id =
        UUID.fromString(
            field(book(request(SeedIds.vehicle(1), "OIL_CHANGE", dayAt(10, 0)), "k-race"), "id"));

    List<ResponseEntity<String>> responses =
        runInParallel(10, index -> () -> cancel(id, "k-cancel-" + index));

    assertThat(responses)
        .allSatisfy(response -> assertThat(response.getStatusCode().value()).isEqualTo(200));
    assertThat(statusOf(id.toString())).isEqualTo("CANCELLED");
    assertThat(confirmedCount()).isZero();
  }

  @Test
  void should_create_one_appointment_when_parallel_requests_share_an_idempotency_key()
      throws Exception {
    BookAppointmentDto request = request(SeedIds.vehicle(1), "OIL_CHANGE", dayAt(10, 0));

    List<ResponseEntity<String>> responses =
        runInParallel(8, index -> () -> book(request, "k-same"));

    List<ResponseEntity<String>> created =
        responses.stream().filter(response -> response.getStatusCode().value() == 201).toList();
    List<ResponseEntity<String>> rejected =
        responses.stream().filter(response -> response.getStatusCode().value() != 201).toList();
    assertThat(created).isNotEmpty();
    assertThat(created.stream().map(response -> field(response, "id")).distinct()).hasSize(1);
    assertThat(rejected)
        .allSatisfy(response -> {
          assertThat(response.getStatusCode().value()).isEqualTo(409);
          assertThat(field(response, "code")).isEqualTo("KUSS_IDEMPOTENCY_IN_FLIGHT");
        });
    assertThat(confirmedCount()).isEqualTo(1);
  }

  private List<ResponseEntity<String>> runInParallel(
      int count, IntFunction<Supplier<ResponseEntity<String>>> call) throws Exception {
    CountDownLatch startGate = new CountDownLatch(1);
    List<ResponseEntity<String>> responses = new ArrayList<>();
    try (ExecutorService executor = Executors.newFixedThreadPool(count)) {
      List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
      for (int index = 0; index < count; index++) {
        Supplier<ResponseEntity<String>> task = call.apply(index);
        futures.add(
            executor.submit(() -> {
              startGate.await();
              return task.get();
            }));
      }
      startGate.countDown();
      for (Future<ResponseEntity<String>> future : futures) {
        responses.add(future.get(30, TimeUnit.SECONDS));
      }
    }
    return responses;
  }

  private ResponseEntity<String> book(BookAppointmentDto request, String key) {
    return http.post()
        .uri("/api/v1/appointments")
        .header(KEY_HEADER, key)
        .contentType(MediaType.APPLICATION_JSON)
        .body(jsonMapper.writeValueAsString(request))
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

  private String field(ResponseEntity<String> response, String name) {
    return jsonMapper.readTree(response.getBody()).get(name).asString();
  }

  private String statusOf(String appointmentId) {
    return jdbcClient
        .sql("SELECT status FROM appointment WHERE id = :id")
        .param("id", UUID.fromString(appointmentId))
        .query(String.class)
        .single();
  }

  private int confirmedCount() {
    return jdbcClient
        .sql("SELECT count(*) FROM appointment WHERE status = 'CONFIRMED'")
        .query(Integer.class)
        .single();
  }

  private BookAppointmentDto request(UUID vehicleId, String serviceTypeCode, OffsetDateTime start) {
    return BookAppointmentDto.builder()
        .dealershipId(SeedIds.LONDON_DEALERSHIP_ID)
        .vehicleId(vehicleId)
        .serviceTypeCode(serviceTypeCode)
        .desiredStartTime(start)
        .build();
  }
}
