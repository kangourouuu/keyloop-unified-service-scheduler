package com.keyloop.scheduler.common.error;

import com.keyloop.scheduler.common.constants.ErrorMessageConstants;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Replaces Spring Boot's default error page so that a servlet container error dispatch, or a direct
 * request to {@code /error}, leaves as the same ProblemDetail as every other failure. It hands the
 * original status to {@link SchedulerGlobalHandler} by throwing.
 */
@Slf4j
@RestController
class SchedulerErrorController implements ErrorController {

  @RequestMapping("/error")
  void error(HttpServletRequest request) {
    Integer status = (Integer) request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
    if (status == null || status == HttpStatus.NOT_FOUND.value()) {
      throw new SchedulerException(
          ErrorCode.KUSS_RESOURCE_NOT_FOUND, ErrorMessageConstants.RESOURCE_NOT_FOUND);
    }
    if (HttpStatusCode.valueOf(status).is5xxServerError()) {
      log.error(
          "Container error dispatch status={}",
          status,
          request.getAttribute(RequestDispatcher.ERROR_EXCEPTION));
      throw new SchedulerException(
          ErrorCode.KUSS_INTERNAL_ERROR, ErrorMessageConstants.UNEXPECTED_ERROR);
    }
    throw new ResponseStatusException(HttpStatusCode.valueOf(status));
  }
}
