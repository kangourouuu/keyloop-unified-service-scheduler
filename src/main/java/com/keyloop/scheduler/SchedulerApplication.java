package com.keyloop.scheduler;

import com.keyloop.scheduler.common.constants.HeaderConstants;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import java.time.Clock;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@OpenAPIDefinition(info = @Info(title = "Unified Service Scheduler API", version = "v1"))
@EnableScheduling
public class SchedulerApplication {

  public static void main(String[] args) {
    SpringApplication.run(SchedulerApplication.class, args);
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  OpenApiCustomizer idempotencyKeyOptionalInSpec() {
    return openApi ->
        openApi
            .getPaths()
            .values()
            .forEach(path ->
                path.readOperations().stream()
                    .filter(operation -> operation.getParameters() != null)
                    .flatMap(operation -> operation.getParameters().stream())
                    .filter(
                        parameter -> HeaderConstants.IDEMPOTENCY_KEY.equals(parameter.getName()))
                    .forEach(parameter -> parameter.setRequired(false)));
  }
}
