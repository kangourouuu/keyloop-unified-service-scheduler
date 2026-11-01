package com.keyloop.scheduler.common.web;

import com.keyloop.scheduler.audit.dto.RequestAuditDto;
import com.keyloop.scheduler.audit.service.RequestAuditService;
import com.keyloop.scheduler.common.constants.ApiPathConstants;
import com.keyloop.scheduler.common.constants.HeaderConstants;
import com.keyloop.scheduler.common.constants.RequestConstants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestAuditFilter extends OncePerRequestFilter {

  private static final List<String> UNAUDITED_PREFIXES =
      List.of("/actuator", "/v3/api-docs", "/swagger-ui");
  private static final String BOOKING_METHOD = HttpMethod.POST.name();
  private static final List<String> BOOKING_PAYLOAD_FIELDS =
      List.of("dealershipId", "vehicleId", "serviceTypeCode", "desiredStartTime");
  private static final int PAYLOAD_CACHE_LIMIT = 4096;
  private static final int METHOD_MAX_LENGTH = 10;
  private static final int PATH_MAX_LENGTH = 255;

  private final Clock clock;
  private final RequestAuditService requestAuditService;
  private final Executor auditExecutor;
  private final ObjectMapper objectMapper;

  /**
   * Assigns the request id, echoes it in the response header and, unless the path is exempt, hands
   * one audit row to the audit executor after the response is built, so a slow or failing audit
   * write never delays or fails the request.
   */
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String requestId = UUID.randomUUID().toString();
    MDC.put(RequestConstants.REQUEST_ID_MDC_KEY, requestId);
    response.setHeader(HeaderConstants.REQUEST_ID, requestId);
    request.setAttribute(RequestConstants.REQUEST_ID_ATTRIBUTE, requestId);
    boolean isAudited = isAudited(request.getServletPath());
    ContentCachingRequestWrapper wrapped =
        new ContentCachingRequestWrapper(request, PAYLOAD_CACHE_LIMIT);
    long startNanos = System.nanoTime();
    try {
      chain.doFilter(wrapped, response);
    } finally {
      if (isAudited) {
        submit(toAudit(wrapped, response, requestId, startNanos));
      }
      MDC.remove(RequestConstants.REQUEST_ID_MDC_KEY);
    }
  }

  private static boolean isAudited(String servletPath) {
    return UNAUDITED_PREFIXES.stream().noneMatch(servletPath::startsWith);
  }

  private void submit(RequestAuditDto audit) {
    try {
      auditExecutor.execute(() -> write(audit));
    } catch (RejectedExecutionException ex) {
      requestAuditService.reject(audit);
    }
  }

  private void write(RequestAuditDto audit) {
    try {
      requestAuditService.write(audit);
    } catch (RuntimeException ex) {
      requestAuditService.reject(audit);
    }
  }

  /**
   * Builds the audit row from the finished request: method and path truncated to the column length,
   * the final status, the error code the handler left on the request, and, for bookings only, the
   * whitelisted payload fields.
   */
  private RequestAuditDto toAudit(
      ContentCachingRequestWrapper request,
      HttpServletResponse response,
      String requestId,
      long startNanos) {
    return RequestAuditDto.builder()
        .id(UUID.randomUUID())
        .occurredAt(clock.instant().truncatedTo(ChronoUnit.MICROS))
        .requestId(requestId)
        .httpMethod(truncate(request.getMethod(), METHOD_MAX_LENGTH))
        .path(truncate(request.getServletPath(), PATH_MAX_LENGTH))
        .httpStatus(response.getStatus())
        .errorCode((String) request.getAttribute(RequestConstants.ERROR_CODE_ATTRIBUTE))
        .durationMs((int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos))
        .payload(payloadOf(request))
        .build();
  }

  private String payloadOf(ContentCachingRequestWrapper request) {
    if (!BOOKING_METHOD.equals(request.getMethod())
        || !ApiPathConstants.APPOINTMENTS.equals(request.getServletPath())) {
      return null;
    }
    return sanitize(request.getContentAsByteArray());
  }

  private String sanitize(byte[] body) {
    try {
      JsonNode root = objectMapper.readTree(body);
      if (!root.isObject()) {
        return null;
      }
      ObjectNode payload = objectMapper.createObjectNode();
      BOOKING_PAYLOAD_FIELDS.stream()
          .filter(root::has)
          .forEach(field -> payload.set(field, root.get(field)));
      return objectMapper.writeValueAsString(payload);
    } catch (JacksonException ex) {
      return null;
    }
  }

  private static String truncate(String value, int maxLength) {
    return value.length() > maxLength ? value.substring(0, maxLength) : value;
  }
}
