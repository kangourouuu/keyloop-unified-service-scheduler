package com.keyloop.scheduler.common.web;

import org.apache.catalina.Valve;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.valves.ErrorReportValve;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

@Configuration(proxyBeanMethods = false)
class ErrorReportConfiguration {

  /**
   * Swaps the host's Tomcat error report valve for {@link ProblemDetailErrorReportValve}. Ordered
   * last so that it runs after Spring Boot has added its own valve, which it then removes.
   */
  @Bean
  @Order(Ordered.LOWEST_PRECEDENCE)
  WebServerFactoryCustomizer<TomcatServletWebServerFactory> problemDetailErrorReport() {
    return new WebServerFactoryCustomizer<>() {
      @Override
      public void customize(TomcatServletWebServerFactory factory) {
        factory.addContextCustomizers(context -> {
          StandardHost host = (StandardHost) context.getParent();
          for (Valve valve : host.getPipeline().getValves()) {
            if (valve instanceof ErrorReportValve) {
              host.getPipeline().removeValve(valve);
            }
          }
          host.setErrorReportValveClass(ProblemDetailErrorReportValve.class.getName());
          host.getPipeline().addValve(new ProblemDetailErrorReportValve());
        });
      }
    };
  }
}
