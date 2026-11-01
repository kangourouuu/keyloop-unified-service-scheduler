package com.keyloop.scheduler.availability.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.keyloop.scheduler.IntegrationTestSupport;
import com.keyloop.scheduler.SeedIds;
import com.keyloop.scheduler.common.constants.ApiPathConstants;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

class AvailabilityApiIT extends IntegrationTestSupport {

  private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

  @Test
  void should_return_20_half_hour_slots_with_capacity_3_for_oil_change_in_london() {
    JsonNode body =
        jsonOf(availabilityUri(SeedIds.LONDON_DEALERSHIP_ID, sevenDaysAhead(), "OIL_CHANGE"));

    assertThat(body.get("slots").size()).isEqualTo(20);
    assertThat(body.get("slots").get(0).get("availableCapacity").asInt()).isEqualTo(3);
  }

  @Test
  void should_keep_the_local_offset_on_slot_start_times_for_london_and_new_york() {
    LocalDate date = sevenDaysAhead();

    OffsetDateTime londonFirst =
        firstStart(availabilityUri(SeedIds.LONDON_DEALERSHIP_ID, date, "OIL_CHANGE"));
    OffsetDateTime newYorkFirst =
        firstStart(availabilityUri(SeedIds.NEW_YORK_DEALERSHIP_ID, date, "OIL_CHANGE"));

    assertThat(londonFirst.getOffset())
        .isEqualTo(LONDON.getRules().getOffset(londonFirst.toInstant()));
    assertThat(newYorkFirst.getOffset())
        .isEqualTo(NEW_YORK.getRules().getOffset(newYorkFirst.toInstant()));
  }

  @Test
  void should_lower_capacity_for_a_booked_technician_and_keep_the_next_slot_free() {
    LocalDate date = sevenDaysAhead();
    Instant nine = date.atTime(9, 0).atZone(LONDON).toInstant();
    Instant tenThirty = date.atTime(10, 30).atZone(LONDON).toInstant();
    insertAppointment(
        SeedIds.LONDON_BAY_1_ID,
        SeedIds.LONDON_TECH_1_ID,
        SeedIds.VEHICLE_1_ID,
        nine,
        tenThirty,
        "CONFIRMED");

    JsonNode body = jsonOf(availabilityUri(SeedIds.LONDON_DEALERSHIP_ID, date, "BRAKE_REPAIR"));

    assertThat(capacityAt(body, nine)).isEqualTo(1);
    assertThat(capacityAt(body, tenThirty)).isEqualTo(2);
  }

  @Test
  void should_return_400_when_date_is_malformed() {
    ResponseEntity<String> response =
        get(
            availabilityPath(SeedIds.LONDON_DEALERSHIP_ID)
                + "?date=2026-13-01&serviceType=OIL_CHANGE");

    assertThat(response.getStatusCode().value()).isEqualTo(400);
  }

  @Test
  void should_return_400_when_service_type_is_missing() {
    ResponseEntity<String> response =
        get(availabilityPath(SeedIds.LONDON_DEALERSHIP_ID) + "?date=" + sevenDaysAhead());

    assertThat(response.getStatusCode().value()).isEqualTo(400);
  }

  @Test
  void should_return_400_when_service_type_is_blank_or_longer_than_40_characters() {
    ResponseEntity<String> blank =
        get(availabilityUri(SeedIds.LONDON_DEALERSHIP_ID, sevenDaysAhead(), " "));
    ResponseEntity<String> tooLong =
        get(availabilityUri(SeedIds.LONDON_DEALERSHIP_ID, sevenDaysAhead(), "S".repeat(41)));

    assertThat(blank.getStatusCode().value()).isEqualTo(400);
    assertThat(blank.getBody()).contains("KUSS_VALIDATION_FAILED");
    assertThat(tooLong.getStatusCode().value()).isEqualTo(400);
    assertThat(tooLong.getBody()).contains("KUSS_VALIDATION_FAILED");
  }

  @Test
  void should_return_404_for_unknown_service_type() {
    ResponseEntity<String> response =
        get(availabilityUri(SeedIds.LONDON_DEALERSHIP_ID, sevenDaysAhead(), "NOPE"));

    assertThat(response.getStatusCode().value()).isEqualTo(404);
  }

  @Test
  void should_return_404_for_unknown_dealership() {
    ResponseEntity<String> response =
        get(availabilityUri(UUID.randomUUID(), sevenDaysAhead(), "OIL_CHANGE"));

    assertThat(response.getStatusCode().value()).isEqualTo(404);
  }

  @Test
  void should_return_400_for_a_past_date() {
    ResponseEntity<String> response =
        get(
            availabilityUri(
                SeedIds.LONDON_DEALERSHIP_ID, LocalDate.now(clock).minusDays(1), "OIL_CHANGE"));

    assertThat(response.getStatusCode().value()).isEqualTo(400);
  }

  @Test
  void should_return_400_for_a_date_beyond_the_postgres_range() {
    ResponseEntity<String> response =
        get(availabilityUri(SeedIds.LONDON_DEALERSHIP_ID, LocalDate.MIN, "OIL_CHANGE"));

    assertThat(response.getStatusCode().value()).isEqualTo(400);
  }

  @Test
  void should_return_400_when_date_is_beyond_the_90_day_horizon() {
    ResponseEntity<String> response =
        get(
            availabilityUri(
                SeedIds.LONDON_DEALERSHIP_ID, LocalDate.now(clock).plusDays(91), "OIL_CHANGE"));

    assertThat(response.getStatusCode().value()).isEqualTo(400);
  }

  private static String availabilityPath(UUID dealershipId) {
    return ApiPathConstants.DEALERSHIPS + "/" + dealershipId + "/availability";
  }

  private static String availabilityUri(UUID dealershipId, LocalDate date, String serviceType) {
    return availabilityPath(dealershipId) + "?date=" + date + "&serviceType=" + serviceType;
  }

  private JsonNode jsonOf(String uri) {
    return jsonMapper.readTree(get(uri).getBody());
  }

  private OffsetDateTime firstStart(String uri) {
    return OffsetDateTime.parse(jsonOf(uri).get("slots").get(0).get("startTime").asString());
  }

  private static int capacityAt(JsonNode body, Instant start) {
    return body.get("slots")
        .valueStream()
        .filter(slot ->
            OffsetDateTime.parse(slot.get("startTime").asString()).toInstant().equals(start))
        .findFirst()
        .map(slot -> slot.get("availableCapacity").asInt())
        .orElseThrow(() -> new AssertionError("no slot starts at " + start));
  }
}
