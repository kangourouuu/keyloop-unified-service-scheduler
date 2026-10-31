package com.keyloop.scheduler.audit;

import static com.keyloop.scheduler.common.constants.MetricConstants.AUDIT_WRITES;
import static com.keyloop.scheduler.common.constants.MetricConstants.STATUS_FAILURE;
import static com.keyloop.scheduler.common.constants.MetricConstants.STATUS_SUCCESS;
import static com.keyloop.scheduler.common.constants.MetricConstants.TAG_STATUS;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.sql.Types;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

@Slf4j
@Service
class RequestAuditServiceImpl implements RequestAuditService {

  private static final String INSERT_SQL =
      """
      INSERT INTO request_audit (id, occurred_at, request_id, http_method, path, http_status,
                                 error_code, duration_ms, request_payload)
      VALUES (:id, :occurredAt, :requestId, :httpMethod, :path, :httpStatus,
              :errorCode, :durationMs, CAST(:payload AS jsonb))
      """;

  private final JdbcClient jdbcClient;
  private final Counter successes;
  private final Counter failures;

  RequestAuditServiceImpl(JdbcClient jdbcClient, MeterRegistry meterRegistry) {
    this.jdbcClient = jdbcClient;
    this.successes = meterRegistry.counter(AUDIT_WRITES, TAG_STATUS, STATUS_SUCCESS);
    this.failures = meterRegistry.counter(AUDIT_WRITES, TAG_STATUS, STATUS_FAILURE);
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void write(RequestAuditDto audit) {
    try {
      insert(audit);
      successes.increment();
    } catch (RuntimeException ex) {
      TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
      failures.increment();
      log.warn(
          "Request audit write failed requestId={} path={} status={}",
          audit.requestId(),
          audit.path(),
          audit.httpStatus(),
          ex);
    }
  }

  @Override
  public void reject(RequestAuditDto audit) {
    failures.increment();
    log.warn(
        "Request audit dropped requestId={} method={} path={} status={} errorCode={}",
        audit.requestId(),
        audit.httpMethod(),
        audit.path(),
        audit.httpStatus(),
        audit.errorCode());
  }

  private void insert(RequestAuditDto audit) {
    jdbcClient
        .sql(INSERT_SQL)
        .param("id", audit.id())
        .param("occurredAt", Timestamp.from(audit.occurredAt()))
        .param("requestId", audit.requestId())
        .param("httpMethod", audit.httpMethod())
        .param("path", audit.path())
        .param("httpStatus", audit.httpStatus())
        .param("errorCode", audit.errorCode(), Types.VARCHAR)
        .param("durationMs", audit.durationMs())
        .param("payload", audit.payload(), Types.VARCHAR)
        .update();
  }
}
