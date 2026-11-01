package com.keyloop.scheduler;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgres() {
    DockerImageName image =
        DockerImageName.parse("docker.io/library/postgres:17")
            .asCompatibleSubstituteFor("postgres");
    return new PostgreSQLContainer(image).withCommand("postgres", "-c", "deadlock_timeout=100ms");
  }
}
