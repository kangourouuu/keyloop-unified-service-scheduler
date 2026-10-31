package com.keyloop.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SchedulerApplicationIT extends IntegrationTestSupport {

  @Test
  void should_start_with_clock_and_database() {
    assertThat(clock).isNotNull();
    assertThat(jdbcClient.sql("SELECT 1").query(Integer.class).single()).isEqualTo(1);
  }
}
