package com.keyloop.scheduler.appointment;

import static com.keyloop.scheduler.SeedIds.CUSTOMER_1_ID;
import static com.keyloop.scheduler.SeedIds.LONDON_BAY_1_ID;
import static com.keyloop.scheduler.SeedIds.LONDON_BAY_2_ID;
import static com.keyloop.scheduler.SeedIds.LONDON_DEALERSHIP_ID;
import static com.keyloop.scheduler.SeedIds.LONDON_TECH_1_ID;
import static com.keyloop.scheduler.SeedIds.LONDON_TECH_2_ID;
import static com.keyloop.scheduler.SeedIds.VEHICLE_1_ID;
import static com.keyloop.scheduler.SeedIds.VEHICLE_2_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keyloop.scheduler.IntegrationTestSupport;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

class SchemaConstraintsIT extends IntegrationTestSupport {

  private static final String BAY_CONSTRAINT = "excl_appointment_bay_overlap";
  private static final String TECH_CONSTRAINT = "excl_appointment_tech_overlap";
  private static final String VEHICLE_CONSTRAINT = "excl_appointment_vehicle_overlap";
  private static final String COMMITTED = "COMMITTED";
  private static final Set<String> CONCURRENT_CONFLICT_STATES = Set.of("23P01", "40P01");
  private static final Instant NINE = Instant.parse("2030-01-07T09:00:00Z");
  private static final Instant NINE_THIRTY = Instant.parse("2030-01-07T09:30:00Z");
  private static final Instant TEN = Instant.parse("2030-01-07T10:00:00Z");
  private static final Instant ELEVEN = Instant.parse("2030-01-07T11:00:00Z");
  private static final String RAW_INSERT_SQL =
      """
      INSERT INTO appointment (id, dealership_id, customer_id, vehicle_id, service_bay_id, technician_id,
                               service_type_code, start_time, end_time, status, created_at)
      VALUES (?, ?, ?, ?, ?, ?, 'OIL_CHANGE', ?, ?, 'CONFIRMED', now())
      """;

  @Autowired private DataSource dataSource;

  @Test
  void should_reject_overlapping_bay_booking() {
    insertAppointment(LONDON_BAY_1_ID, LONDON_TECH_1_ID, VEHICLE_1_ID, NINE, TEN, "CONFIRMED");

    assertViolates(
        BAY_CONSTRAINT,
        () ->
            insertAppointment(
                LONDON_BAY_1_ID, LONDON_TECH_2_ID, VEHICLE_2_ID, NINE_THIRTY, ELEVEN, "CONFIRMED"));
  }

  @Test
  void should_reject_overlapping_technician_booking() {
    insertAppointment(LONDON_BAY_1_ID, LONDON_TECH_1_ID, VEHICLE_1_ID, NINE, TEN, "CONFIRMED");

    assertViolates(
        TECH_CONSTRAINT,
        () ->
            insertAppointment(
                LONDON_BAY_2_ID, LONDON_TECH_1_ID, VEHICLE_2_ID, NINE_THIRTY, ELEVEN, "CONFIRMED"));
  }

  @Test
  void should_reject_overlapping_vehicle_booking() {
    insertAppointment(LONDON_BAY_1_ID, LONDON_TECH_1_ID, VEHICLE_1_ID, NINE, TEN, "CONFIRMED");

    assertViolates(
        VEHICLE_CONSTRAINT,
        () ->
            insertAppointment(
                LONDON_BAY_2_ID, LONDON_TECH_2_ID, VEHICLE_1_ID, NINE_THIRTY, ELEVEN, "CONFIRMED"));
  }

  @Test
  void should_allow_back_to_back_bookings_on_same_bay_technician_and_vehicle() {
    insertAppointment(LONDON_BAY_1_ID, LONDON_TECH_1_ID, VEHICLE_1_ID, NINE, TEN, "CONFIRMED");
    insertAppointment(LONDON_BAY_1_ID, LONDON_TECH_1_ID, VEHICLE_1_ID, TEN, ELEVEN, "CONFIRMED");

    assertThat(countConfirmedAppointments()).isEqualTo(2);
  }

  @Test
  void should_allow_rebooking_a_window_after_its_appointment_is_cancelled() {
    UUID cancelledId =
        insertAppointment(LONDON_BAY_1_ID, LONDON_TECH_1_ID, VEHICLE_1_ID, NINE, TEN, "CONFIRMED");
    jdbcClient
        .sql("UPDATE appointment SET status = 'CANCELLED', cancelled_at = now() WHERE id = :id")
        .param("id", cancelledId)
        .update();

    insertAppointment(LONDON_BAY_1_ID, LONDON_TECH_1_ID, VEHICLE_1_ID, NINE, TEN, "CONFIRMED");

    assertThat(countConfirmedAppointments()).isEqualTo(1);
  }

  @Test
  void should_commit_only_one_of_two_concurrent_overlapping_bay_inserts() throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<String> first =
          executor.submit(() -> rawInsertOutcome(start, LONDON_TECH_1_ID, VEHICLE_1_ID));
      Future<String> second =
          executor.submit(() -> rawInsertOutcome(start, LONDON_TECH_2_ID, VEHICLE_2_ID));
      start.countDown();
      List<String> outcomes =
          List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

      assertThat(outcomes).filteredOn(COMMITTED::equals).hasSize(1);
      assertThat(outcomes)
          .filteredOn(outcome -> !COMMITTED.equals(outcome))
          .hasSize(1)
          .allMatch(CONCURRENT_CONFLICT_STATES::contains);
    }
    assertThat(countConfirmedAppointments()).isEqualTo(1);
  }

  @Test
  void should_route_request_audit_row_dated_2000_to_default_partition() {
    UUID auditId = UUID.randomUUID();
    try {
      jdbcClient
          .sql(
              """
              INSERT INTO request_audit (id, occurred_at, request_id, http_method, path, http_status, duration_ms)
              VALUES (:id, :occurredAt, 'req-default', 'GET', '/api/v1/test', 200, 1)
              """)
          .param("id", auditId)
          .param("occurredAt", Timestamp.from(Instant.parse("2000-01-01T00:00:00Z")))
          .update();

      Integer rows =
          jdbcClient
              .sql("SELECT count(*) FROM request_audit_default WHERE id = :id")
              .param("id", auditId)
              .query(Integer.class)
              .single();
      assertThat(rows).isEqualTo(1);
    } finally {
      jdbcClient.sql("DELETE FROM request_audit WHERE id = :id").param("id", auditId).update();
    }
  }

  private void assertViolates(String constraint, Runnable insert) {
    assertThatThrownBy(insert::run)
        .isInstanceOf(DataAccessException.class)
        .satisfies(ex -> assertThat(constraintOf(ex)).isEqualTo(constraint));
  }

  private static String constraintOf(Throwable ex) {
    Throwable cause = ex;
    while (!(cause instanceof PSQLException)) {
      cause = cause.getCause();
    }
    return ((PSQLException) cause).getServerErrorMessage().getConstraint();
  }

  private String rawInsertOutcome(CountDownLatch start, UUID techId, UUID vehicleId)
      throws InterruptedException {
    start.await();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(RAW_INSERT_SQL)) {
      connection.setAutoCommit(false);
      bindConfirmedInsert(statement, techId, vehicleId);
      statement.executeUpdate();
      connection.commit();
      return COMMITTED;
    } catch (SQLException ex) {
      return ex.getSQLState();
    }
  }

  private static void bindConfirmedInsert(PreparedStatement statement, UUID techId, UUID vehicleId)
      throws SQLException {
    statement.setObject(1, UUID.randomUUID());
    statement.setObject(2, LONDON_DEALERSHIP_ID);
    statement.setObject(3, CUSTOMER_1_ID);
    statement.setObject(4, vehicleId);
    statement.setObject(5, LONDON_BAY_1_ID);
    statement.setObject(6, techId);
    statement.setTimestamp(7, Timestamp.from(NINE));
    statement.setTimestamp(8, Timestamp.from(TEN));
  }

  private int countConfirmedAppointments() {
    return jdbcClient
        .sql("SELECT count(*) FROM appointment WHERE status = 'CONFIRMED'")
        .query(Integer.class)
        .single();
  }
}
