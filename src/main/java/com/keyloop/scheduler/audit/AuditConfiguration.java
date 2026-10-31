package com.keyloop.scheduler.audit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
class AuditConfiguration {

  private static final int CORE_POOL_SIZE = 2;
  private static final int QUEUE_CAPACITY = 1000;
  private static final int SHUTDOWN_WAIT_SECONDS = 5;

  @Bean
  ThreadPoolTaskExecutor auditExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(CORE_POOL_SIZE);
    executor.setMaxPoolSize(CORE_POOL_SIZE);
    executor.setQueueCapacity(QUEUE_CAPACITY);
    executor.setThreadNamePrefix("audit-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(SHUTDOWN_WAIT_SECONDS);
    return executor;
  }
}
