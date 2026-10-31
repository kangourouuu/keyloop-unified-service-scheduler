package com.keyloop.scheduler.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.keyloop.scheduler.IntegrationTestSupport;
import com.keyloop.scheduler.SeedIds;
import com.keyloop.scheduler.common.constants.HeaderConstants;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;

class PoolExhaustionIT extends IntegrationTestSupport {

  private static final long POOL_TIMEOUT_MILLIS = 250;

  @Autowired private DataSource dataSource;

  @Autowired private Clock clock;

  @Autowired private JsonMapper jsonMapper;

  @Test
  void should_return_503_service_busy_on_cancel_when_no_connection_is_free() throws SQLException {
    ResponseEntity<String> response =
        whilePoolIsExhausted(() ->
            http.post()
                .uri("/api/v1/appointments/" + UUID.randomUUID() + "/cancel")
                .header(HeaderConstants.IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                .retrieve()
                .toEntity(String.class));

    assertServiceBusy(response);
  }

  @Test
  void should_return_503_service_busy_on_reads_when_no_connection_is_free() throws SQLException {
    String day = LocalDate.now(clock).plusDays(7).toString();

    List<ResponseEntity<String>> responses =
        whilePoolIsExhausted(() ->
            List.of(
                get("/api/v1/dealerships"),
                get(
                    "/api/v1/dealerships/"
                        + SeedIds.LONDON_DEALERSHIP_ID
                        + "/appointments?date="
                        + day),
                get(
                    "/api/v1/dealerships/%s/availability?date=%s&serviceType=OIL_CHANGE"
                        .formatted(SeedIds.LONDON_DEALERSHIP_ID, day))));

    responses.forEach(this::assertServiceBusy);
  }

  private void assertServiceBusy(ResponseEntity<String> response) {
    assertThat(response.getStatusCode().value()).isEqualTo(503);
    assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
    assertThat(jsonMapper.readTree(response.getBody()).get("code").asString())
        .isEqualTo("KUSS_SERVICE_BUSY");
  }

  private <T> T whilePoolIsExhausted(Supplier<T> request) throws SQLException {
    HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
    long originalTimeout = pool.getConnectionTimeout();
    List<Connection> held = new ArrayList<>();
    try {
      pool.getHikariConfigMXBean().setConnectionTimeout(POOL_TIMEOUT_MILLIS);
      for (int i = 0; i < pool.getMaximumPoolSize(); i++) {
        held.add(pool.getConnection());
      }
      return request.get();
    } finally {
      pool.getHikariConfigMXBean().setConnectionTimeout(originalTimeout);
      for (Connection connection : held) {
        connection.close();
      }
    }
  }

  private ResponseEntity<String> get(String uri) {
    return http.get().uri(uri).retrieve().toEntity(String.class);
  }
}
