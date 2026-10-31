package com.keyloop.scheduler.audit;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
class MaintenanceServiceImpl implements MaintenanceService, SmartLifecycle {

  private static final String PARTITION_PREFIX = "request_audit_p";
  private static final int PARTITIONS_AHEAD_DAYS = 7;
  private static final int RETENTION_DAYS = 14;
  private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
  private static final String LIST_PARTITIONS_SQL =
      """
      SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
       WHERE i.inhparent = 'request_audit'::regclass
         AND c.relname ~ '^request_audit_p[0-9]{8}$'
      """;
  private static final String DELETE_DEFAULT_ROWS_SQL =
      "DELETE FROM request_audit_default WHERE occurred_at < :cutoff";
  private static final String DELETE_IDEMPOTENCY_KEYS_SQL =
      "DELETE FROM idempotency_key WHERE created_at < :cutoff";

  private final JdbcClient jdbcClient;
  private final Clock clock;
  private volatile boolean running;

  MaintenanceServiceImpl(JdbcClient jdbcClient, Clock clock) {
    this.jdbcClient = jdbcClient;
    this.clock = clock;
  }

  @Override
  @Scheduled(cron = "0 5 0 * * *", zone = "UTC")
  public void runDaily() {
    Instant now = clock.instant();
    LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
    for (int ahead = 0; ahead <= PARTITIONS_AHEAD_DAYS; ahead++) {
      LocalDate day = today.plusDays(ahead);
      runStep("create partition " + day, () -> createPartition(day));
    }
    LocalDate retentionStart = today.minusDays(RETENTION_DAYS);
    runStep("drop partitions", () -> dropPartitionsBefore(retentionStart));
    runStep("delete default rows", () -> deleteDefaultRowsBefore(startOf(retentionStart)));
    runStep(
        "delete idempotency keys", () -> deleteIdempotencyKeysBefore(now.minus(IDEMPOTENCY_TTL)));
  }

  @Override
  public void start() {
    runDaily();
    running = true;
  }

  @Override
  public void stop() {
    running = false;
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public int getPhase() {
    return Integer.MIN_VALUE;
  }

  private void createPartition(LocalDate day) {
    jdbcClient
        .sql(
            "CREATE TABLE IF NOT EXISTS "
                + partitionName(day)
                + " PARTITION OF request_audit FOR VALUES FROM ('"
                + day
                + " 00:00:00+00') TO ('"
                + day.plusDays(1)
                + " 00:00:00+00')")
        .update();
  }

  private void dropPartitionsBefore(LocalDate cutoff) {
    jdbcClient.sql(LIST_PARTITIONS_SQL).query(String.class).list().stream()
        .filter(name -> dayOf(name).isBefore(cutoff))
        .forEach(name -> jdbcClient.sql("DROP TABLE IF EXISTS " + name).update());
  }

  private void deleteDefaultRowsBefore(Instant cutoff) {
    jdbcClient.sql(DELETE_DEFAULT_ROWS_SQL).param("cutoff", Timestamp.from(cutoff)).update();
  }

  private void deleteIdempotencyKeysBefore(Instant cutoff) {
    jdbcClient.sql(DELETE_IDEMPOTENCY_KEYS_SQL).param("cutoff", Timestamp.from(cutoff)).update();
  }

  private static String partitionName(LocalDate day) {
    return PARTITION_PREFIX + DateTimeFormatter.BASIC_ISO_DATE.format(day);
  }

  private static LocalDate dayOf(String partitionName) {
    return LocalDate.parse(
        partitionName.substring(PARTITION_PREFIX.length()), DateTimeFormatter.BASIC_ISO_DATE);
  }

  private static Instant startOf(LocalDate day) {
    return day.atStartOfDay(ZoneOffset.UTC).toInstant();
  }

  private static void runStep(String step, Runnable action) {
    try {
      action.run();
    } catch (RuntimeException ex) {
      log.warn("Audit maintenance step failed: {}", step, ex);
    }
  }
}
