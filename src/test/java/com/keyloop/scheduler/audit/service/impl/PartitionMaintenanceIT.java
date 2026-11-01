package com.keyloop.scheduler.audit.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.keyloop.scheduler.IntegrationTestSupport;
import com.keyloop.scheduler.audit.service.MaintenanceService;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PartitionMaintenanceIT extends IntegrationTestSupport {

  private static final String PARTITIONS_SQL =
      """
      SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
       WHERE i.inhparent = 'request_audit'::regclass
      """;
  private static final String COUNT_DEFAULT_ROWS_SQL =
      """
      SELECT count(*) FROM request_audit_default
       WHERE occurred_at >= :from AND occurred_at < :to
      """;
  private static final String INSERT_AUDIT_SQL =
      """
      INSERT INTO request_audit (id, occurred_at, request_id, http_method, path, http_status, duration_ms)
      VALUES (:id, :occurredAt, :requestId, 'GET', '/nope', 404, 1)
      """;
  private static final String INSERT_IDEMPOTENCY_SQL =
      """
      INSERT INTO idempotency_key (idempotency_key, request_hash, created_at)
      VALUES (:key, 'hash', :createdAt)
      """;

  @Autowired private MaintenanceService maintenanceService;

  @Test
  void should_create_partitions_for_today_through_seven_days_ahead_at_startup() {
    LocalDate today = LocalDate.now(clock);
    Set<String> expected =
        IntStream.rangeClosed(0, 7)
            .mapToObj(today::plusDays)
            .map(PartitionMaintenanceIT::partitionName)
            .collect(Collectors.toSet());

    assertThat(partitionNames()).containsAll(expected);
  }

  @Test
  void should_keep_default_partition_empty_for_today_after_startup() {
    LocalDate today = LocalDate.now(clock);

    assertThat(countDefaultRows(startOf(today), startOf(today.plusDays(1)))).isZero();
  }

  @AfterEach
  void dropRetentionBoundaryFixtures() {
    LocalDate cutoff = retentionCutoff();
    jdbcClient.sql("DROP TABLE IF EXISTS " + partitionName(cutoff.minusDays(1))).update();
    jdbcClient.sql("DROP TABLE IF EXISTS " + partitionName(cutoff)).update();
    jdbcClient
        .sql("DELETE FROM request_audit_default WHERE occurred_at >= :from AND occurred_at < :to")
        .param("from", Timestamp.from(startOf(cutoff.minusDays(1))))
        .param("to", Timestamp.from(startOf(cutoff.plusDays(1))))
        .update();
  }

  @Test
  void should_drop_partitions_older_than_six_months_and_keep_newer() {
    LocalDate cutoff = retentionCutoff();
    createPartition(cutoff.minusDays(1));
    createPartition(cutoff);

    maintenanceService.runDaily();

    assertThat(partitionNames())
        .doesNotContain(partitionName(cutoff.minusDays(1)))
        .contains(partitionName(cutoff));
  }

  @Test
  void should_delete_default_rows_older_than_six_months_and_keep_newer() {
    LocalDate cutoff = retentionCutoff();
    insertAudit(startOf(cutoff.minusDays(1)));
    insertAudit(startOf(cutoff));

    maintenanceService.runDaily();

    assertThat(countDefaultRows(startOf(cutoff.minusDays(1)), startOf(cutoff))).isZero();
    assertThat(countDefaultRows(startOf(cutoff), startOf(cutoff.plusDays(1)))).isEqualTo(1);
  }

  @Test
  void should_delete_idempotency_keys_older_than_twenty_four_hours_and_keep_newer() {
    Instant now = clock.instant();
    insertIdempotencyKey("stale-key", now.minus(Duration.ofHours(25)));
    insertIdempotencyKey("fresh-key", now.minus(Duration.ofHours(1)));

    maintenanceService.runDaily();

    assertThat(
            jdbcClient
                .sql("SELECT idempotency_key FROM idempotency_key")
                .query(String.class)
                .list())
        .containsExactly("fresh-key");
  }

  @Test
  void should_run_daily_twice_without_error_and_keep_the_same_partitions() {
    maintenanceService.runDaily();
    Set<String> firstRun = partitionNames();

    maintenanceService.runDaily();

    assertThat(partitionNames()).isEqualTo(firstRun);
  }

  private LocalDate retentionCutoff() {
    return LocalDate.now(clock).minusMonths(6);
  }

  private Set<String> partitionNames() {
    return Set.copyOf(jdbcClient.sql(PARTITIONS_SQL).query(String.class).list());
  }

  private long countDefaultRows(Instant from, Instant to) {
    return jdbcClient
        .sql(COUNT_DEFAULT_ROWS_SQL)
        .param("from", Timestamp.from(from))
        .param("to", Timestamp.from(to))
        .query(Long.class)
        .single();
  }

  private void createPartition(LocalDate day) {
    jdbcClient
        .sql(
            "CREATE TABLE "
                + partitionName(day)
                + " PARTITION OF request_audit FOR VALUES FROM ('"
                + startOf(day)
                + "') TO ('"
                + startOf(day.plusDays(1))
                + "')")
        .update();
  }

  private void insertAudit(Instant occurredAt) {
    jdbcClient
        .sql(INSERT_AUDIT_SQL)
        .param("id", UUID.randomUUID())
        .param("occurredAt", Timestamp.from(occurredAt))
        .param("requestId", UUID.randomUUID().toString())
        .update();
  }

  private void insertIdempotencyKey(String key, Instant createdAt) {
    jdbcClient
        .sql(INSERT_IDEMPOTENCY_SQL)
        .param("key", key)
        .param("createdAt", Timestamp.from(createdAt))
        .update();
  }

  private static String partitionName(LocalDate day) {
    return "request_audit_p" + DateTimeFormatter.BASIC_ISO_DATE.format(day);
  }

  private static Instant startOf(LocalDate day) {
    return day.atStartOfDay(ZoneOffset.UTC).toInstant();
  }
}
