package com.keyloop.scheduler.appointment.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keyloop.scheduler.IntegrationTestSupport;
import com.keyloop.scheduler.SeedIds;
import com.keyloop.scheduler.appointment.dto.AppointmentResultDto;
import com.keyloop.scheduler.appointment.dto.BookAppointmentDto;
import com.keyloop.scheduler.appointment.service.AppointmentService;
import com.keyloop.scheduler.common.constants.MetricConstants;
import com.keyloop.scheduler.common.error.ErrorCode;
import com.keyloop.scheduler.common.error.SchedulerException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class VehicleOverlapIT extends IntegrationTestSupport {

  private static final ZoneId LONDON = ZoneId.of("Europe/London");
  private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

  @Autowired private AppointmentService appointmentService;

  @Autowired private Clock clock;

  @Autowired private MeterRegistry meterRegistry;

  @Test
  void should_reject_an_overlapping_service_for_the_same_vehicle_without_retrying() {
    book(LONDON_DEALERSHIP, SeedIds.vehicle(1), "OIL_CHANGE", dayAt(15, 0), "k-first");
    double retriesBefore = retries();

    assertRejected(
        LONDON_DEALERSHIP, SeedIds.vehicle(1), "BRAKE_REPAIR", dayAt(14, 30), "k-second");

    assertThat(retries()).isEqualTo(retriesBefore);
  }

  @Test
  void should_reject_the_same_vehicle_at_another_dealership_for_the_same_instant() {
    OffsetDateTime londonStart = dayAt(15, 0);
    book(LONDON_DEALERSHIP, SeedIds.vehicle(1), "OIL_CHANGE", londonStart, "k-london");
    OffsetDateTime newYorkSameInstant = londonStart.toInstant().atZone(NEW_YORK).toOffsetDateTime();

    assertRejected(
        SeedIds.NEW_YORK_DEALERSHIP_ID,
        SeedIds.vehicle(1),
        "OIL_CHANGE",
        newYorkSameInstant,
        "k-ny");
  }

  @Test
  void should_accept_exactly_one_of_two_concurrent_requests_for_the_same_vehicle()
      throws Exception {
    CountDownLatch startGate = new CountDownLatch(1);
    OffsetDateTime start = dayAt(11, 0);
    List<Future<String>> futures;
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      futures =
          List.of(
              executor.submit(() -> attempt(startGate, SeedIds.vehicle(5), start, "k-race-a")),
              executor.submit(() -> attempt(startGate, SeedIds.vehicle(5), start, "k-race-b")));
      startGate.countDown();
      List<String> outcomes =
          List.of(
              futures.get(0).get(10, TimeUnit.SECONDS), futures.get(1).get(10, TimeUnit.SECONDS));

      assertThat(outcomes).filteredOn("201"::equals).hasSize(1);
      assertThat(outcomes).filteredOn("KUSS_VEHICLE_ALREADY_BOOKED"::equals).hasSize(1);
    }
  }

  @Test
  void should_report_vehicle_conflict_rather_than_no_capacity_when_the_slot_is_full() {
    book(LONDON_DEALERSHIP, SeedIds.vehicle(2), "OIL_CHANGE", dayAt(10, 0), "k-fill-2");
    book(LONDON_DEALERSHIP, SeedIds.vehicle(3), "OIL_CHANGE", dayAt(10, 0), "k-fill-3");
    book(LONDON_DEALERSHIP, SeedIds.vehicle(4), "OIL_CHANGE", dayAt(10, 0), "k-fill-4");

    assertRejected(LONDON_DEALERSHIP, SeedIds.vehicle(2), "BRAKE_REPAIR", dayAt(10, 0), "k-full");
  }

  private static final UUID LONDON_DEALERSHIP = SeedIds.LONDON_DEALERSHIP_ID;

  private String attempt(CountDownLatch startGate, UUID vehicleId, OffsetDateTime start, String key)
      throws InterruptedException {
    startGate.await();
    try {
      return String.valueOf(
          book(LONDON_DEALERSHIP, vehicleId, "OIL_CHANGE", start, key).httpStatus());
    } catch (SchedulerException ex) {
      return ex.getCode().name();
    }
  }

  private void assertRejected(
      UUID dealershipId, UUID vehicleId, String serviceTypeCode, OffsetDateTime start, String key) {
    assertThatThrownBy(() -> book(dealershipId, vehicleId, serviceTypeCode, start, key))
        .isInstanceOfSatisfying(
            SchedulerException.class,
            ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.KUSS_VEHICLE_ALREADY_BOOKED));
  }

  private AppointmentResultDto book(
      UUID dealershipId, UUID vehicleId, String serviceTypeCode, OffsetDateTime start, String key) {
    return appointmentService.book(
        BookAppointmentDto.builder()
            .dealershipId(dealershipId)
            .vehicleId(vehicleId)
            .serviceTypeCode(serviceTypeCode)
            .desiredStartTime(start)
            .build(),
        key);
  }

  private double retries() {
    return meterRegistry.find(MetricConstants.BOOKING_GIST_RETRIES).counters().stream()
        .mapToDouble(Counter::count)
        .sum();
  }

  private OffsetDateTime dayAt(int hour, int minute) {
    return LocalDate.now(clock).plusDays(7).atTime(hour, minute).atZone(LONDON).toOffsetDateTime();
  }
}
