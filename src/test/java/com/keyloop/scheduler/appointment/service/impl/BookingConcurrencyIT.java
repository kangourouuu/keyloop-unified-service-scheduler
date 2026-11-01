package com.keyloop.scheduler.appointment.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.keyloop.scheduler.IntegrationTestSupport;
import com.keyloop.scheduler.SeedIds;
import com.keyloop.scheduler.appointment.dto.AppointmentDto;
import com.keyloop.scheduler.appointment.dto.AppointmentResultDto;
import com.keyloop.scheduler.appointment.dto.BookAppointmentDto;
import com.keyloop.scheduler.appointment.service.AppointmentService;
import com.keyloop.scheduler.common.error.SchedulerException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class BookingConcurrencyIT extends IntegrationTestSupport {

  private static final int CONTENDERS = 20;

  @Autowired private AppointmentService appointmentService;

  @Test
  void should_book_exactly_3_bays_when_20_requests_race_for_oil_change_slots() throws Exception {
    List<String> outcomes = burst("OIL_CHANGE", CONTENDERS);

    assertThat(outcomes).filteredOn("201"::equals).hasSize(3);
    assertThat(outcomes).filteredOn("KUSS_NO_CAPACITY"::equals).hasSize(17);
    assertConfirmedUsage(3);
    Set<UUID> bays =
        Set.of(SeedIds.LONDON_BAY_1_ID, SeedIds.LONDON_BAY_2_ID, SeedIds.LONDON_BAY_3_ID);
    assertThat(jdbcClient.sql("SELECT service_bay_id FROM appointment").query(UUID.class).list())
        .allMatch(bays::contains);
  }

  @Test
  void should_book_exactly_2_technicians_when_20_requests_race_for_brake_repair_slots()
      throws Exception {
    List<String> outcomes = burst("BRAKE_REPAIR", CONTENDERS);

    assertThat(outcomes).filteredOn("201"::equals).hasSize(2);
    assertThat(outcomes).filteredOn("KUSS_NO_CAPACITY"::equals).hasSize(18);
    assertConfirmedUsage(2);
    Set<UUID> technicians = Set.of(SeedIds.LONDON_TECH_1_ID, SeedIds.LONDON_TECH_3_ID);
    assertThat(jdbcClient.sql("SELECT technician_id FROM appointment").query(UUID.class).list())
        .allMatch(technicians::contains);
  }

  @Test
  void should_free_capacity_when_a_confirmed_booking_is_cancelled() {
    for (int vehicle = 1; vehicle <= 3; vehicle++) {
      book(SeedIds.vehicle(vehicle), "OIL_CHANGE", dayAt(10, 0), UUID.randomUUID().toString());
    }
    assertThat(outcomeOf(SeedIds.vehicle(4), "OIL_CHANGE")).isEqualTo("KUSS_NO_CAPACITY");

    jdbcClient
        .sql(
            "UPDATE appointment SET status = 'CANCELLED', cancelled_at = now() WHERE vehicle_id = :vehicleId")
        .param("vehicleId", SeedIds.vehicle(1))
        .update();

    assertThat(outcomeOf(SeedIds.vehicle(4), "OIL_CHANGE")).isEqualTo("201");
  }

  @Test
  void should_link_customer_vehicle_technician_and_bay_on_the_stored_booking() {
    AppointmentDto appointment =
        book(SeedIds.vehicle(1), "BRAKE_REPAIR", dayAt(10, 0), UUID.randomUUID().toString())
            .appointment();

    assertThat(appointment.customerId()).isEqualTo(SeedIds.CUSTOMER_1_ID);
    assertThat(appointment.endTime()).isEqualTo(appointment.startTime().plusSeconds(90 * 60));
    Integer skilled =
        jdbcClient
            .sql(
                "SELECT count(*) FROM technician_skill WHERE technician_id = :technicianId AND service_type_code = 'BRAKE_REPAIR'")
            .param("technicianId", appointment.technicianId())
            .query(Integer.class)
            .single();
    Boolean bayActive =
        jdbcClient
            .sql(
                "SELECT active FROM service_bay WHERE id = :bayId AND dealership_id = :dealershipId")
            .param("bayId", appointment.serviceBayId())
            .param("dealershipId", SeedIds.LONDON_DEALERSHIP_ID)
            .query(Boolean.class)
            .single();
    assertThat(skilled).isEqualTo(1);
    assertThat(bayActive).isTrue();
  }

  private List<String> burst(String serviceTypeCode, int contenders) throws Exception {
    OffsetDateTime start = dayAt(10, 0);
    CountDownLatch startGate = new CountDownLatch(1);
    List<Future<String>> futures = new ArrayList<>();
    try (ExecutorService executor = Executors.newFixedThreadPool(contenders)) {
      for (int vehicle = 1; vehicle <= contenders; vehicle++) {
        UUID vehicleId = SeedIds.vehicle(vehicle);
        String key = UUID.randomUUID().toString();
        futures.add(
            executor.submit(() -> {
              startGate.await();
              return bookOutcome(vehicleId, serviceTypeCode, start, key);
            }));
      }
      startGate.countDown();
      List<String> outcomes = new ArrayList<>();
      for (Future<String> future : futures) {
        outcomes.add(future.get(30, TimeUnit.SECONDS));
      }
      return outcomes;
    }
  }

  private String bookOutcome(
      UUID vehicleId, String serviceTypeCode, OffsetDateTime start, String key) {
    try {
      return String.valueOf(book(vehicleId, serviceTypeCode, start, key).httpStatus());
    } catch (SchedulerException ex) {
      return ex.getCode().name();
    }
  }

  private String outcomeOf(UUID vehicleId, String serviceTypeCode) {
    return bookOutcome(vehicleId, serviceTypeCode, dayAt(10, 0), UUID.randomUUID().toString());
  }

  private AppointmentResultDto book(
      UUID vehicleId, String serviceTypeCode, OffsetDateTime start, String key) {
    return appointmentService.book(
        BookAppointmentDto.builder()
            .dealershipId(SeedIds.LONDON_DEALERSHIP_ID)
            .vehicleId(vehicleId)
            .serviceTypeCode(serviceTypeCode)
            .desiredStartTime(start)
            .build(),
        key);
  }

  private void assertConfirmedUsage(int expected) {
    assertThat(countConfirmed()).isEqualTo(expected);
    assertThat(distinctColumn("service_bay_id")).isEqualTo(expected);
    assertThat(distinctColumn("technician_id")).isEqualTo(expected);
  }

  private int countConfirmed() {
    return jdbcClient
        .sql("SELECT count(*) FROM appointment WHERE status = 'CONFIRMED'")
        .query(Integer.class)
        .single();
  }

  private int distinctColumn(String column) {
    return jdbcClient
        .sql("SELECT count(DISTINCT " + column + ") FROM appointment WHERE status = 'CONFIRMED'")
        .query(Integer.class)
        .single();
  }
}
