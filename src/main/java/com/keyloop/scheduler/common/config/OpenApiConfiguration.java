package com.keyloop.scheduler.common.config;

import com.keyloop.scheduler.common.constants.HeaderConstants;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

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
