package com.keyloop.scheduler.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.keyloop.scheduler.audit.dto.RequestAuditDto;
import com.keyloop.scheduler.audit.service.RequestAuditService;
import java.time.Clock;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class RequestAuditFilterTest {

  private static final String BOOKING_PATH = "/api/v1/appointments";

  @Test
  void should_hand_audit_to_reject_when_executor_refuses_the_task() throws Exception {
    RequestAuditService auditService = mock(RequestAuditService.class);
    Executor fullQueue =
        task -> {
          throw new RejectedExecutionException("audit queue full");
        };
    RequestAuditFilter filter =
        new RequestAuditFilter(
            Clock.systemUTC(), auditService, fullQueue, JsonMapper.builder().build());
    MockHttpServletRequest request = new MockHttpServletRequest("POST", BOOKING_PATH);
    request.setServletPath(BOOKING_PATH);

    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    ArgumentCaptor<RequestAuditDto> dropped = ArgumentCaptor.forClass(RequestAuditDto.class);
    verify(auditService).reject(dropped.capture());
    verify(auditService, never()).write(any());
    assertThat(dropped.getValue().path()).isEqualTo(BOOKING_PATH);
    assertThat(dropped.getValue().httpStatus()).isEqualTo(200);
  }
}
