package com.keyloop.scheduler.common.web;

import com.keyloop.scheduler.common.constants.HeaderConstants;
import com.keyloop.scheduler.common.constants.RequestConstants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestAuditFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String requestId = UUID.randomUUID().toString();
    MDC.put(RequestConstants.REQUEST_ID_MDC_KEY, requestId);
    response.setHeader(HeaderConstants.REQUEST_ID, requestId);
    request.setAttribute(RequestConstants.REQUEST_ID_ATTRIBUTE, requestId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(RequestConstants.REQUEST_ID_MDC_KEY);
    }
  }
}
