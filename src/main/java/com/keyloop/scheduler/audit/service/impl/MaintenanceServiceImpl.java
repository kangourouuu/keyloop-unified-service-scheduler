package com.keyloop.scheduler.audit.service.impl;

import com.keyloop.scheduler.appointment.repository.IdempotencyKeyRepository;
import com.keyloop.scheduler.audit.repository.RequestAuditRepository;
import com.keyloop.scheduler.audit.service.MaintenanceService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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

  @PersistenceContext private EntityManager entityManager;

  private final RequestAuditRepository requestAuditRepository;
  private final IdempotencyKeyRepository idempotencyKeyRepository;
  private final Clock clock;
  private final TransactionTemplate transactionTemplate;
  private volatile boolean running;

  MaintenanceServiceImpl(
      RequestAuditRepository requestAuditRepository,
      IdempotencyKeyRepository idempotencyKeyRepository,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.requestAuditRepository = requestAuditRepository;
    this.idempotencyKeyRepository = idempotencyKeyRepository;
    this.clock = clock;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
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
    runStep(
        "delete default rows",
        () -> requestAuditRepository.deleteDefaultRowsBefore(startOf(retentionStart)));
    runStep(
        "delete idempotency keys",
        () -> idempotencyKeyRepository.deleteCreatedBefore(now.minus(IDEMPOTENCY_TTL)));
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
    entityManager
        .createNativeQuery(
            "CREATE TABLE IF NOT EXISTS "
                + partitionName(day)
                + " PARTITION OF request_audit FOR VALUES FROM ('"
                + day
                + " 00:00:00+00') TO ('"
                + day.plusDays(1)
                + " 00:00:00+00')")
        .executeUpdate();
  }

  private void dropPartitionsBefore(LocalDate cutoff) {
    List<String> partitions = entityManager.createNativeQuery(LIST_PARTITIONS_SQL).getResultList();
    partitions.stream()
        .filter(name -> dayOf(name).isBefore(cutoff))
        .forEach(name ->
            entityManager.createNativeQuery("DROP TABLE IF EXISTS " + name).executeUpdate());
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

  private void runStep(String step, Runnable action) {
    try {
      transactionTemplate.executeWithoutResult(status -> action.run());
    } catch (RuntimeException ex) {
      log.warn("Audit maintenance step failed: {}", step, ex);
    }
  }
}
