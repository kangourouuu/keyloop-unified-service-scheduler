package com.keyloop.scheduler.audit.service.impl;

import static com.keyloop.scheduler.common.constants.MetricConstants.AUDIT_WRITES;
import static com.keyloop.scheduler.common.constants.MetricConstants.STATUS_FAILURE;
import static com.keyloop.scheduler.common.constants.MetricConstants.TAG_STATUS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.keyloop.scheduler.IntegrationTestSupport;
import com.keyloop.scheduler.SeedIds;
import com.keyloop.scheduler.audit.dto.RequestAuditDto;
import com.keyloop.scheduler.audit.service.RequestAuditService;
import com.keyloop.scheduler.common.constants.ApiPathConstants;
import com.keyloop.scheduler.common.constants.HeaderConstants;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Builder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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

  @Autowired private JsonMapper jsonMapper;

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
    requestIdOf("/actuator");
    requestIdOf("/actuator/health");
    awaitAuditRow(requestIdOf("/nope"));

    assertThat(countByPath("/actuator")).isZero();
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
            .path(ApiPathConstants.APPOINTMENTS)
            .httpStatus(400)
            .durationMs(1)
            .payload("{not json")
            .build());

    assertThat(failureCount()).isEqualTo(failuresBefore + 1);
  }

  @Test
  void should_audit_booking_payload_with_only_whitelisted_fields() {
    String body =
        bookingBody(SeedIds.vehicle(1), "OIL_CHANGE", dayAt(10, 0))
            .replace("}", ",\"note\":\"x\"}");

    AuditRow row = awaitAuditRow(bookingRequestId(body));

    assertThat(row.httpStatus()).isEqualTo(201);
    assertThat(row.errorCode()).isNull();
    JsonNode payload = jsonMapper.readTree(row.payload());
    assertThat(payload.size()).isEqualTo(4);
    assertThat(payload.has("note")).isFalse();
  }

  @Test
  void should_audit_409_with_no_capacity_code_when_all_bays_are_busy() {
    Instant start = dayAt(10, 0).toInstant();
    Instant end = start.plus(Duration.ofMinutes(30));
    insertAppointment(
        SeedIds.LONDON_BAY_1_ID,
        SeedIds.LONDON_TECH_1_ID,
        SeedIds.vehicle(1),
        start,
        end,
        "CONFIRMED");
    insertAppointment(
        SeedIds.LONDON_BAY_2_ID,
        SeedIds.LONDON_TECH_2_ID,
        SeedIds.vehicle(2),
        start,
        end,
        "CONFIRMED");
    insertAppointment(
        SeedIds.LONDON_BAY_3_ID,
        SeedIds.LONDON_TECH_3_ID,
        SeedIds.vehicle(3),
        start,
        end,
        "CONFIRMED");

    AuditRow row =
        awaitAuditRow(
            bookingRequestId(bookingBody(SeedIds.vehicle(4), "OIL_CHANGE", dayAt(10, 0))));

    assertThat(row.httpStatus()).isEqualTo(409);
    assertThat(row.errorCode()).isEqualTo("KUSS_NO_CAPACITY");
  }

  @Test
  void should_audit_malformed_booking_as_400_with_null_payload() {
    AuditRow row = awaitAuditRow(bookingRequestId("{"));

    assertThat(row.httpStatus()).isEqualTo(400);
    assertThat(row.errorCode()).isEqualTo("KUSS_VALIDATION_FAILED");
    assertThat(row.payload()).isNull();
  }

  @Test
  void should_audit_availability_read_with_no_error_code_and_null_payload() {
    String uri =
        "/api/v1/dealerships/"
            + SeedIds.LONDON_DEALERSHIP_ID
            + "/availability?date="
            + LocalDate.now(clock).plusDays(7)
            + "&serviceType=OIL_CHANGE";

    AuditRow row = awaitAuditRow(requestIdOf(uri));

    assertThat(row.httpStatus()).isEqualTo(200);
    assertThat(row.errorCode()).isNull();
    assertThat(row.payload()).isNull();
  }

  private String bookingRequestId(String body) {
    return http.post()
        .uri(ApiPathConstants.APPOINTMENTS)
        .header(HeaderConstants.IDEMPOTENCY_KEY, UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .retrieve()
        .toEntity(String.class)
        .getHeaders()
        .getFirst(HeaderConstants.REQUEST_ID);
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
            AuditRow.builder()
                .httpStatus(rs.getInt("http_status"))
                .errorCode(rs.getString("error_code"))
                .path(rs.getString("path"))
                .payload(rs.getString("payload"))
                .build())
        .single();
  }

  private long countByPath(String path) {
    return jdbcClient.sql(COUNT_BY_PATH_SQL).param("path", path).query(Long.class).single();
  }

  private double failureCount() {
    return meterRegistry.get(AUDIT_WRITES).tag(TAG_STATUS, STATUS_FAILURE).counter().count();
  }

  @Builder
  private record AuditRow(int httpStatus, String errorCode, String path, String payload) {}
}
