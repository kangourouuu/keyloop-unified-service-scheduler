package com.keyloop.scheduler.common.error;

import com.keyloop.scheduler.IntegrationTestSupport;
import com.keyloop.scheduler.SeedIds;
import com.keyloop.scheduler.common.constants.ApiPathConstants;
import com.keyloop.scheduler.common.constants.HeaderConstants;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

class PoolExhaustionIT extends IntegrationTestSupport {

  private static final long POOL_TIMEOUT_MILLIS = 250;

  @Autowired private DataSource dataSource;

  @Test
  void should_return_503_service_busy_on_cancel_when_no_connection_is_free() throws SQLException {
    ResponseEntity<String> response =
        whilePoolIsExhausted(() ->
            http.post()
                .uri(
                    ApiPathConstants.APPOINTMENTS
                        + "/"
                        + UUID.randomUUID()
                        + ApiPathConstants.CANCEL)
                .header(HeaderConstants.IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                .retrieve()
                .toEntity(String.class));

    assertServiceBusy(response);
  }

  @Test
  void should_return_503_service_busy_on_reads_when_no_connection_is_free() throws SQLException {
    String day = sevenDaysAhead().toString();

    List<ResponseEntity<String>> responses =
        whilePoolIsExhausted(() ->
            List.of(
                get(ApiPathConstants.DEALERSHIPS),
                get(
                    ApiPathConstants.DEALERSHIPS
                        + "/"
                        + SeedIds.LONDON_DEALERSHIP_ID
                        + "/appointments?date="
                        + day),
                get(
                    ApiPathConstants.DEALERSHIPS
                        + "/%s/availability?date=%s&serviceType=OIL_CHANGE"
                            .formatted(SeedIds.LONDON_DEALERSHIP_ID, day))));

    responses.forEach(this::assertServiceBusy);
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
}
