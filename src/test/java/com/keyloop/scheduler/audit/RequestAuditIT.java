package com.keyloop.scheduler.audit;

import static com.keyloop.scheduler.common.constants.MetricConstants.AUDIT_WRITES;
import static com.keyloop.scheduler.common.constants.MetricConstants.STATUS_FAILURE;
import static com.keyloop.scheduler.common.constants.MetricConstants.TAG_STATUS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.keyloop.scheduler.IntegrationTestSupport;
import com.keyloop.scheduler.common.constants.HeaderConstants;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RequestAuditIT extends IntegrationTestSupport {

  private static final int PATH_MAX_LENGTH = 255;
  private static final String AUDIT_BY_REQUEST_ID_SQL =
      """
      SELECT http_status, error_code, path, request_payload::text AS payload
      FROM request_audit
      WHERE request_id = :requestId
      """;
  private static final String COUNT_BY_PATH_SQL =
      "SELECT count(*) FROM request_audit WHERE path = :path";

  @Autowired private RequestAuditService requestAuditService;

  @Autowired private MeterRegistry meterRegistry;

  @Test
  void should_audit_500_with_internal_error_code_when_handler_throws() {
    AuditRow row = awaitAuditRow(requestIdOf("/api/test/boom"));

    assertThat(row.httpStatus()).isEqualTo(500);
    assertThat(row.errorCode()).isEqualTo("KUSS_INTERNAL_ERROR");
    assertThat(row.payload()).isNull();
  }

  @Test
  void should_audit_404_with_resource_not_found_code() {
    AuditRow row = awaitAuditRow(requestIdOf("/nope"));

    assertThat(row.httpStatus()).isEqualTo(404);
    assertThat(row.errorCode()).isEqualTo("KUSS_RESOURCE_NOT_FOUND");
  }

  @Test
  void should_not_audit_actuator_requests() {
    requestIdOf("/actuator/health");
    awaitAuditRow(requestIdOf("/nope"));

    assertThat(countByPath("/actuator/health")).isZero();
  }

  @Test
  void should_truncate_audited_path_to_column_length() {
    String path = "/" + "a".repeat(299);
    AuditRow row = awaitAuditRow(requestIdOf(path));

    assertThat(row.path()).hasSize(PATH_MAX_LENGTH).isEqualTo(path.substring(0, PATH_MAX_LENGTH));
  }

  @Test
  void should_count_failed_write_without_throwing() {
    double failuresBefore = failureCount();

    requestAuditService.write(
        RequestAuditDto.builder()
            .id(UUID.randomUUID())
            .occurredAt(Instant.now())
            .requestId(UUID.randomUUID().toString())
            .httpMethod("POST")
            .path("/api/v1/appointments")
            .httpStatus(400)
            .durationMs(1)
            .payload("{not json")
            .build());

    assertThat(failureCount()).isEqualTo(failuresBefore + 1);
  }

  private String requestIdOf(String uri) {
    return http.get()
        .uri(uri)
        .retrieve()
        .toEntity(String.class)
        .getHeaders()
        .getFirst(HeaderConstants.REQUEST_ID);
  }

  private AuditRow awaitAuditRow(String requestId) {
    await()
        .atMost(Duration.ofSeconds(5))
        .until(() ->
            jdbcClient
                    .sql("SELECT count(*) FROM request_audit WHERE request_id = :requestId")
                    .param("requestId", requestId)
                    .query(Long.class)
                    .single()
                > 0);
    return jdbcClient
        .sql(AUDIT_BY_REQUEST_ID_SQL)
        .param("requestId", requestId)
        .query((rs, rowNum) ->
            new AuditRow(
                rs.getInt("http_status"),
                rs.getString("error_code"),
                rs.getString("path"),
                rs.getString("payload")))
        .single();
  }

  private long countByPath(String path) {
    return jdbcClient.sql(COUNT_BY_PATH_SQL).param("path", path).query(Long.class).single();
  }

  private double failureCount() {
    return meterRegistry.get(AUDIT_WRITES).tag(TAG_STATUS, STATUS_FAILURE).counter().count();
  }

  private record AuditRow(int httpStatus, String errorCode, String path, String payload) {}
}
