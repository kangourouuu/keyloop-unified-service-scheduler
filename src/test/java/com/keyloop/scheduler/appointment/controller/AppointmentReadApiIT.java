package com.keyloop.scheduler.appointment.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.keyloop.scheduler.SeedIds;
import com.keyloop.scheduler.common.constants.ApiPathConstants;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

class AppointmentReadApiIT extends AppointmentApiSupport {

  @Test
  void should_read_back_an_appointment_and_list_the_day_in_start_order() {
    String late = id(book(request(SeedIds.vehicle(2), "OIL_CHANGE", dayAt(11, 0)), "k-late"));
    String early = id(book(request(SeedIds.vehicle(1), "OIL_CHANGE", dayAt(10, 0)), "k-early"));
    cancel(UUID.fromString(early), "k-early-cancel");

    ResponseEntity<String> single = get(ApiPathConstants.APPOINTMENTS + "/" + late);
    JsonNode day = jsonMapper.readTree(dayList(sevenDaysAhead()).getBody());

    assertThat(single.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(id(single)).isEqualTo(late);
    assertThat(day.size()).isEqualTo(2);
    assertThat(day.get(0).get("id").asString()).isEqualTo(early);
    assertThat(day.get(0).get("status").asString()).isEqualTo("CANCELLED");
    assertThat(day.get(1).get("id").asString()).isEqualTo(late);
  }

  @Test
  void should_list_a_day_up_to_365_days_back_and_90_days_ahead() {
    LocalDate today = LocalDate.now(clock);

    assertThat(dayList(today.minusDays(365)).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(dayList(today.plusDays(90)).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertValidationFailed(dayList(today.minusDays(366)));
    assertValidationFailed(dayList(today.plusDays(91)));
    assertValidationFailed(dayList(LocalDate.MIN));
  }

  @Test
  void should_publish_the_appointment_paths_in_openapi() {
    ResponseEntity<String> docs = get("/v3/api-docs");

    assertThat(docs.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(docs.getBody()).contains(ApiPathConstants.APPOINTMENTS);
  }
}
