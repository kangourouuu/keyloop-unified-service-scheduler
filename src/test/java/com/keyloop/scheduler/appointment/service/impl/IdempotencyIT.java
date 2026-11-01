package com.keyloop.scheduler.appointment.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.keyloop.scheduler.IntegrationTestSupport;
import com.keyloop.scheduler.SeedIds;
import com.keyloop.scheduler.appointment.dto.AppointmentResultDto;
import com.keyloop.scheduler.appointment.dto.BookAppointmentDto;
import com.keyloop.scheduler.appointment.service.AppointmentService;
import com.keyloop.scheduler.common.error.ErrorCode;
import com.keyloop.scheduler.common.error.SchedulerException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class IdempotencyIT extends IntegrationTestSupport {

  @Autowired private AppointmentService appointmentService;

  @Autowired private DataSource dataSource;

  @Test
  void should_replay_the_stored_result_for_the_same_key_and_request() {
    OffsetDateTime start = dayAt(10, 0);
    AppointmentResultDto first = book(SeedIds.vehicle(1), "OIL_CHANGE", start, "k-replay");

    AppointmentResultDto second = book(SeedIds.vehicle(1), "OIL_CHANGE", start, "k-replay");

    assertThat(second.appointment().id()).isEqualTo(first.appointment().id());
    assertThat(second.httpStatus()).isEqualTo(201);
    assertThat(countAppointments()).isEqualTo(1);
  }

  @Test
  void should_replay_when_the_same_instant_is_sent_with_another_offset() {
    OffsetDateTime start = dayAt(10, 0);
    AppointmentResultDto first = book(SeedIds.vehicle(1), "OIL_CHANGE", start, "k-offset");

    AppointmentResultDto second =
        book(
            SeedIds.vehicle(1),
            "OIL_CHANGE",
            start.withOffsetSameInstant(ZoneOffset.UTC),
            "k-offset");

    assertThat(second.appointment().id()).isEqualTo(first.appointment().id());
    assertThat(countAppointments()).isEqualTo(1);
  }

  @Test
  void should_reject_the_same_key_with_a_different_payload_as_422() {
    book(SeedIds.vehicle(1), "OIL_CHANGE", dayAt(10, 0), "k-mismatch");

    assertThatThrownBy(() -> book(SeedIds.vehicle(2), "OIL_CHANGE", dayAt(10, 0), "k-mismatch"))
        .isInstanceOfSatisfying(
            SchedulerException.class,
            ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.KUSS_IDEMPOTENCY_KEY_MISMATCH));
    assertThat(countAppointments()).isEqualTo(1);
  }

  @Test
  void should_not_store_a_failed_booking_so_the_same_key_succeeds_once_capacity_returns() {
    for (int vehicle = 1; vehicle <= 3; vehicle++) {
      book(SeedIds.vehicle(vehicle), "OIL_CHANGE", dayAt(10, 0), "k-fill-" + vehicle);
    }
    assertThatThrownBy(() -> book(SeedIds.vehicle(4), "OIL_CHANGE", dayAt(10, 0), "k-retry"))
        .isInstanceOfSatisfying(
            SchedulerException.class,
            ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.KUSS_NO_CAPACITY));
    jdbcClient
        .sql(
            "UPDATE appointment SET status = 'CANCELLED', cancelled_at = now() WHERE vehicle_id = :vehicleId")
        .param("vehicleId", SeedIds.vehicle(1))
        .update();

    assertThat(book(SeedIds.vehicle(4), "OIL_CHANGE", dayAt(10, 0), "k-retry").httpStatus())
        .isEqualTo(201);
  }

  @Test
  void should_treat_a_key_older_than_24_hours_as_absent() {
    jdbcClient
        .sql(
            "INSERT INTO idempotency_key (idempotency_key, request_hash, response_status,"
                + " response_body, created_at) VALUES ('k-expired', 'x', 201, '{}', :createdAt)")
        .param("createdAt", Timestamp.from(clock.instant().minus(Duration.ofHours(25))))
        .update();

    AppointmentResultDto booked = book(SeedIds.vehicle(1), "OIL_CHANGE", dayAt(10, 0), "k-expired");

    assertThat(booked.httpStatus()).isEqualTo(201);
    assertThat(countAppointments()).isEqualTo(1);
  }

  @Test
  void should_report_in_flight_when_the_blocking_claim_commits() throws Exception {
    try (Connection holder = holderWithUncommittedClaim("k-held")) {
      int holderPid = backendPid(holder);
      try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
        Future<AppointmentResultDto> waiting =
            executor.submit(() -> book(SeedIds.vehicle(1), "OIL_CHANGE", dayAt(10, 0), "k-held"));
        awaitBlockedBy(holderPid);
        holder.commit();

        assertThat(failureCode(waiting)).isEqualTo(ErrorCode.KUSS_IDEMPOTENCY_IN_FLIGHT);
      }
    }
  }

  @Test
  void should_book_when_the_blocking_claim_is_rolled_back() throws Exception {
    try (Connection holder = holderWithUncommittedClaim("k-released")) {
      int holderPid = backendPid(holder);
      try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
        Future<AppointmentResultDto> waiting =
            executor.submit(
                () -> book(SeedIds.vehicle(1), "OIL_CHANGE", dayAt(10, 0), "k-released"));
        awaitBlockedBy(holderPid);
        holder.rollback();

        assertThat(waiting.get(10, TimeUnit.SECONDS).httpStatus()).isEqualTo(201);
      }
    }
  }

  private Connection holderWithUncommittedClaim(String key) throws SQLException {
    Connection holder = dataSource.getConnection();
    holder.setAutoCommit(false);
    try (PreparedStatement insert =
        holder.prepareStatement(
            "INSERT INTO idempotency_key (idempotency_key, request_hash) VALUES (?, 'x')")) {
      insert.setString(1, key);
      insert.executeUpdate();
    }
    return holder;
  }

  private static int backendPid(Connection connection) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("SELECT pg_backend_pid()");
        ResultSet rows = statement.executeQuery()) {
      rows.next();
      return rows.getInt(1);
    }
  }

  private void awaitBlockedBy(int holderPid) {
    await()
        .atMost(Duration.ofSeconds(5))
        .until(() ->
            jdbcClient
                    .sql(
                        "SELECT count(*) FROM pg_stat_activity WHERE :pid = ANY(pg_blocking_pids(pid))")
                    .param("pid", holderPid)
                    .query(Integer.class)
                    .single()
                > 0);
  }

  private static ErrorCode failureCode(Future<?> future)
      throws InterruptedException, java.util.concurrent.TimeoutException {
    try {
      future.get(10, TimeUnit.SECONDS);
      throw new AssertionError("expected a failure");
    } catch (ExecutionException ex) {
      return ((SchedulerException) ex.getCause()).getCode();
    }
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

  private int countAppointments() {
    return jdbcClient.sql("SELECT count(*) FROM appointment").query(Integer.class).single();
  }
}
