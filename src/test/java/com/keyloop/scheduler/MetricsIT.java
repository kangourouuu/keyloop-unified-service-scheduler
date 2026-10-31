package com.keyloop.scheduler;

import static com.keyloop.scheduler.common.constants.MetricConstants.AVAILABILITY_QUERY_DURATION;
import static com.keyloop.scheduler.common.constants.MetricConstants.BOOKING_REQUESTS;
import static com.keyloop.scheduler.common.constants.MetricConstants.TAG_RESULT;
import static com.keyloop.scheduler.common.constants.MetricConstants.TAG_SERVICE_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

import com.keyloop.scheduler.common.constants.ApiPathConstants;
import com.keyloop.scheduler.common.constants.MetricConstants;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class MetricsIT extends IntegrationTestSupport {

  private static final String OIL_CHANGE = "OIL_CHANGE";

  @Autowired private MeterRegistry meterRegistry;

  @Test
  void should_count_confirmed_booking_by_service_type() {
    double before = bookingCount(OIL_CHANGE, MetricConstants.RESULT_CONFIRMED);

    ResponseEntity<String> response = book(SeedIds.vehicle(1), OIL_CHANGE, dayAt(10, 0));

    assertThat(response.getStatusCode().value()).isEqualTo(201);
    assertThat(bookingCount(OIL_CHANGE, MetricConstants.RESULT_CONFIRMED)).isEqualTo(before + 1);
  }

  @Test
  void should_count_no_capacity_result_when_all_bays_are_busy() {
    Instant start = dayAt(10, 0).toInstant();
    Instant end = start.plus(Duration.ofMinutes(30));
    insertAppointment(
        SeedIds.LONDON_BAY_1_ID,
        SeedIds.LONDON_TECH_1_ID,
        SeedIds.vehicle(1),
        start,
        end,
        "CONFIRMED");
    insertAppointment(
        SeedIds.LONDON_BAY_2_ID,
        SeedIds.LONDON_TECH_2_ID,
        SeedIds.vehicle(2),
        start,
        end,
        "CONFIRMED");
    insertAppointment(
        SeedIds.LONDON_BAY_3_ID,
        SeedIds.LONDON_TECH_3_ID,
        SeedIds.vehicle(3),
        start,
        end,
        "CONFIRMED");
    double before = bookingCount(OIL_CHANGE, "KUSS_NO_CAPACITY");

    ResponseEntity<String> response = book(SeedIds.vehicle(4), OIL_CHANGE, dayAt(10, 0));

    assertThat(response.getStatusCode().value()).isEqualTo(409);
    assertThat(bookingCount(OIL_CHANGE, "KUSS_NO_CAPACITY")).isEqualTo(before + 1);
  }

  @Test
  void should_tag_unknown_service_type_when_code_is_not_in_the_catalog() {
    double before = bookingCount(MetricConstants.UNKNOWN_SERVICE_TYPE, "KUSS_RESOURCE_NOT_FOUND");

    book(SeedIds.vehicle(1), "ZZZ", dayAt(10, 0));

    assertThat(bookingCount(MetricConstants.UNKNOWN_SERVICE_TYPE, "KUSS_RESOURCE_NOT_FOUND"))
        .isEqualTo(before + 1);
  }

  @Test
  void should_tag_unknown_service_type_on_idempotency_mismatch_with_unvalidated_code() {
    String key = UUID.randomUUID().toString();
    book(SeedIds.vehicle(1), OIL_CHANGE, dayAt(10, 0), key);
    double before =
        bookingCount(MetricConstants.UNKNOWN_SERVICE_TYPE, "KUSS_IDEMPOTENCY_KEY_MISMATCH");

    ResponseEntity<String> response = book(SeedIds.vehicle(1), "ZZZ", dayAt(10, 0), key);

    assertThat(response.getStatusCode().value()).isEqualTo(422);
    assertThat(bookingCount(MetricConstants.UNKNOWN_SERVICE_TYPE, "KUSS_IDEMPOTENCY_KEY_MISMATCH"))
        .isEqualTo(before + 1);
    assertThat(meterRegistry.find(BOOKING_REQUESTS).tag(TAG_SERVICE_TYPE, "ZZZ").counters())
        .isEmpty();
  }

  @Test
  void should_count_missing_idempotency_key_as_unknown_service_type() {
    double before =
        bookingCount(MetricConstants.UNKNOWN_SERVICE_TYPE, "KUSS_IDEMPOTENCY_KEY_MISSING");

    ResponseEntity<String> response =
        http.post()
            .uri(ApiPathConstants.APPOINTMENTS)
            .contentType(MediaType.APPLICATION_JSON)
            .body("{}")
            .retrieve()
            .toEntity(String.class);

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(bookingCount(MetricConstants.UNKNOWN_SERVICE_TYPE, "KUSS_IDEMPOTENCY_KEY_MISSING"))
        .isEqualTo(before + 1);
  }

  @Test
  void should_count_boundary_validation_failures_as_unknown_service_type() {
    double before = bookingCount(MetricConstants.UNKNOWN_SERVICE_TYPE, "KUSS_VALIDATION_FAILED");

    ResponseEntity<String> emptyBody = postBooking("{}", "k-" + UUID.randomUUID());
    ResponseEntity<String> malformed = postBooking("{bad", "k-" + UUID.randomUUID());
    ResponseEntity<String> badKey = postBooking("{}", "a b");

    assertThat(emptyBody.getStatusCode().value()).isEqualTo(400);
    assertThat(malformed.getStatusCode().value()).isEqualTo(400);
    assertThat(badKey.getStatusCode().value()).isEqualTo(400);
    assertThat(bookingCount(MetricConstants.UNKNOWN_SERVICE_TYPE, "KUSS_VALIDATION_FAILED"))
        .isEqualTo(before + 3);
  }

  @Test
  void should_not_count_other_endpoints_as_booking_requests() {
    double before =
        bookingCount(MetricConstants.UNKNOWN_SERVICE_TYPE, "KUSS_IDEMPOTENCY_KEY_MISSING");

    http.post()
        .uri(ApiPathConstants.APPOINTMENTS + "/" + UUID.randomUUID() + ApiPathConstants.CANCEL)
        .retrieve()
        .toBodilessEntity();

    assertThat(bookingCount(MetricConstants.UNKNOWN_SERVICE_TYPE, "KUSS_IDEMPOTENCY_KEY_MISSING"))
        .isEqualTo(before);
  }

  @Test
  void should_time_availability_queries() {
    long before = availabilityCount();

    http.get()
        .uri(
            "/api/v1/dealerships/{dealershipId}/availability?date={date}&serviceType=OIL_CHANGE",
            SeedIds.LONDON_DEALERSHIP_ID,
            LocalDate.now(clock).plusDays(7))
        .retrieve()
        .toBodilessEntity();

    assertThat(availabilityCount()).isEqualTo(before + 1);
  }

  @Test
  void should_expose_scheduler_meters_on_prometheus() {
    book(SeedIds.vehicle(1), OIL_CHANGE, dayAt(10, 0));

    String metrics = http.get().uri("/actuator/prometheus").retrieve().body(String.class);

    assertThat(metrics)
        .contains("scheduler_booking_requests_total")
        .contains("scheduler_audit_writes_total");
  }

  private double bookingCount(String serviceType, String result) {
    return meterRegistry
        .get(BOOKING_REQUESTS)
        .tags(TAG_SERVICE_TYPE, serviceType, TAG_RESULT, result)
        .counter()
        .count();
  }

  private long availabilityCount() {
    return meterRegistry.get(AVAILABILITY_QUERY_DURATION).timer().count();
  }

  private ResponseEntity<String> book(
      UUID vehicleId, String serviceTypeCode, OffsetDateTime start) {
    return book(vehicleId, serviceTypeCode, start, UUID.randomUUID().toString());
  }

  private ResponseEntity<String> book(
      UUID vehicleId, String serviceTypeCode, OffsetDateTime start, String idempotencyKey) {
    return postBooking(bookingBody(vehicleId, serviceTypeCode, start), idempotencyKey);
  }
}
