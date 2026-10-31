package com.keyloop.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SchedulerApplicationIT extends IntegrationTestSupport {

  @Autowired private Clock clock;

  @Test
  void should_start_with_clock_and_database() {
    assertThat(clock).isNotNull();
    assertThat(jdbcClient.sql("SELECT 1").query(Integer.class).single()).isEqualTo(1);
  }
}
