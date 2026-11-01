package com.keyloop.scheduler.appointment.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.keyloop.scheduler.SeedIds;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class AppointmentCancelApiIT extends AppointmentApiSupport {

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
  void should_cancel_once_when_parallel_cancels_use_different_keys() throws Exception {
    UUID appointmentId =
        UUID.fromString(id(book(request(SeedIds.vehicle(1), "OIL_CHANGE", dayAt(10, 0)), "k-p0")));
    CountDownLatch startGate = new CountDownLatch(1);
    List<ResponseEntity<String>> responses = new ArrayList<>();
    try (ExecutorService executor = Executors.newFixedThreadPool(6)) {
      List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
      for (int caller = 1; caller <= 6; caller++) {
        String key = "k-parallel-cancel-" + caller;
        futures.add(
            executor.submit(() -> {
              startGate.await();
              return cancel(appointmentId, key);
            }));
      }
      startGate.countDown();
      for (Future<ResponseEntity<String>> future : futures) {
        responses.add(future.get(30, TimeUnit.SECONDS));
      }
    }

    assertThat(responses)
        .allSatisfy(response -> assertThat(response.getStatusCode().value()).isEqualTo(200));
    assertThat(responses)
        .extracting(
            response -> jsonMapper.readTree(response.getBody()).get("cancelledAt").asString())
        .doesNotContainNull()
        .containsOnly(responseCancelledAt(responses.get(0)));
    assertThat(
            jdbcClient
                .sql("SELECT count(*) FROM appointment WHERE id = :id AND status = 'CANCELLED'")
                .param("id", appointmentId)
                .query(Long.class)
                .single())
        .isEqualTo(1L);
  }

  private String responseCancelledAt(ResponseEntity<String> response) {
    return jsonMapper.readTree(response.getBody()).get("cancelledAt").asString();
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
}
