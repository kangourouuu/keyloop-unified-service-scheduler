package com.keyloop.scheduler.appointment.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.keyloop.scheduler.SeedIds;
import com.keyloop.scheduler.common.constants.ApiPathConstants;
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

class AppointmentConflictApiIT extends AppointmentApiSupport {

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
    assertThat(get(ApiPathConstants.APPOINTMENTS + "/" + UUID.randomUUID()).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(cancel(UUID.randomUUID(), "k-cancel-missing").getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(
            get(ApiPathConstants.DEALERSHIPS
                    + "/"
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
}
