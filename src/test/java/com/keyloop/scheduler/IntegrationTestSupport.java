package com.keyloop.scheduler;

import com.keyloop.scheduler.common.constants.ApiPathConstants;
import com.keyloop.scheduler.common.constants.HeaderConstants;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
public abstract class IntegrationTestSupport {

  private static final String INSERT_APPOINTMENT_SQL =
      """
      INSERT INTO appointment (id, dealership_id, customer_id, vehicle_id, service_bay_id, technician_id,
                               service_type_code, start_time, end_time, status, created_at, cancelled_at)
      SELECT :id, :dealershipId, v.customer_id, v.id, :bayId, :techId, 'OIL_CHANGE', :start, :end, :status,
             now(), CASE WHEN :status = 'CANCELLED' THEN now() END
      FROM vehicle v
      WHERE v.id = :vehicleId
      """;

  protected static final ZoneId LONDON = ZoneId.of("Europe/London");

  @LocalServerPort protected int port;

  @Autowired protected JdbcClient jdbcClient;

  @Autowired protected Clock clock;

  @Autowired private TransactionTemplate transactionTemplate;

  protected RestClient http;

  @BeforeEach
  void createHttpClient() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port)
            .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {})
            .build();
  }

  @BeforeEach
  void truncateAppointments() {
    transactionTemplate.executeWithoutResult(status -> {
      jdbcClient.sql("SET LOCAL lock_timeout = '5s'").update();
      jdbcClient.sql("TRUNCATE appointment, idempotency_key").update();
    });
  }

  protected UUID insertAppointment(
      UUID bayId, UUID techId, UUID vehicleId, Instant start, Instant end, String status) {
    UUID id = UUID.randomUUID();
    jdbcClient
        .sql(INSERT_APPOINTMENT_SQL)
        .param("id", id)
        .param("dealershipId", SeedIds.LONDON_DEALERSHIP_ID)
        .param("bayId", bayId)
        .param("techId", techId)
        .param("vehicleId", vehicleId)
        .param("start", Timestamp.from(start))
        .param("end", Timestamp.from(end))
        .param("status", status)
        .update();
    return id;
  }

  protected OffsetDateTime dayAt(int hour, int minute) {
    return LocalDate.now(clock).plusDays(7).atTime(hour, minute).atZone(LONDON).toOffsetDateTime();
  }

  protected static String bookingBody(
      UUID vehicleId, String serviceTypeCode, OffsetDateTime desiredStartTime) {
    return "{\"dealershipId\":\"%s\",\"vehicleId\":\"%s\",\"serviceTypeCode\":\"%s\",\"desiredStartTime\":\"%s\"}"
        .formatted(SeedIds.LONDON_DEALERSHIP_ID, vehicleId, serviceTypeCode, desiredStartTime);
  }

  protected ResponseEntity<String> postBooking(String body, String idempotencyKey) {
    return http.post()
        .uri(ApiPathConstants.APPOINTMENTS)
        .header(HeaderConstants.IDEMPOTENCY_KEY, idempotencyKey)
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .retrieve()
        .toEntity(String.class);
  }
}
