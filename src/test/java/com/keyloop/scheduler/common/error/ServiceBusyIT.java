package com.keyloop.scheduler.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.keyloop.scheduler.IntegrationTestSupport;
import com.keyloop.scheduler.SeedIds;
import com.keyloop.scheduler.common.constants.ApiPathConstants;
import com.keyloop.scheduler.common.constants.HeaderConstants;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

class ServiceBusyIT extends IntegrationTestSupport {

  @Autowired private DataSource dataSource;

  @Test
  void should_return_503_service_busy_when_booking_waits_on_a_locked_table() throws SQLException {
    String key = UUID.randomUUID().toString();

    ResponseEntity<String> stalled = whileAppointmentTableLocked(() -> book(key));
    ResponseEntity<String> retried = book(key);

    assertServiceBusy(stalled);
    assertThat(retried.getStatusCode().value()).isEqualTo(201);
  }

  @Test
  void should_return_503_service_busy_when_cancel_waits_on_a_locked_table() throws SQLException {
    UUID appointmentId = futureAppointment();

    ResponseEntity<String> stalled = whileAppointmentTableLocked(() -> cancel(appointmentId));

    assertServiceBusy(stalled);
  }

  @Test
  void should_return_503_service_busy_when_availability_waits_on_a_locked_table()
      throws SQLException {
    ResponseEntity<String> stalled =
        whileAppointmentTableLocked(() ->
            get(
                ApiPathConstants.DEALERSHIPS
                    + "/%s/availability?date=%s&serviceType=OIL_CHANGE"
                        .formatted(SeedIds.LONDON_DEALERSHIP_ID, sevenDaysAhead())));

    assertServiceBusy(stalled);
  }

  @Test
  void should_return_503_service_busy_when_day_list_waits_on_a_locked_table() throws SQLException {
    ResponseEntity<String> stalled =
        whileAppointmentTableLocked(() ->
            get(
                ApiPathConstants.DEALERSHIPS
                    + "/%s/appointments?date=%s"
                        .formatted(SeedIds.LONDON_DEALERSHIP_ID, sevenDaysAhead())));

    assertServiceBusy(stalled);
  }

  private <T> T whileAppointmentTableLocked(Supplier<T> request) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      connection.createStatement().execute("LOCK TABLE appointment IN ACCESS EXCLUSIVE MODE");
      try {
        return request.get();
      } finally {
        connection.rollback();
      }
    }
  }

  private UUID futureAppointment() {
    Instant start = sevenDaysAhead().atTime(10, 0).atZone(LONDON).toInstant();
    return insertAppointment(
        SeedIds.LONDON_BAY_1_ID,
        SeedIds.LONDON_TECH_1_ID,
        SeedIds.vehicle(1),
        start,
        start.plus(Duration.ofMinutes(30)),
        "CONFIRMED");
  }

  private ResponseEntity<String> book(String key) {
    return postBooking(bookingBody(SeedIds.vehicle(1), "OIL_CHANGE", dayAt(10, 0)), key);
  }

  private ResponseEntity<String> cancel(UUID appointmentId) {
    return http.post()
        .uri(ApiPathConstants.APPOINTMENTS + "/" + appointmentId + ApiPathConstants.CANCEL)
        .header(HeaderConstants.IDEMPOTENCY_KEY, UUID.randomUUID().toString())
        .retrieve()
        .toEntity(String.class);
  }
}
