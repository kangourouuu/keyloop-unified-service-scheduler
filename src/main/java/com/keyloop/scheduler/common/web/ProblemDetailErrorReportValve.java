package com.keyloop.scheduler.common.web;

import com.keyloop.scheduler.common.constants.ErrorMessageConstants;
import com.keyloop.scheduler.common.constants.RequestConstants;
import com.keyloop.scheduler.common.error.ErrorCode;
import java.io.IOException;
import java.io.Writer;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;

/**
 * Writes the error body for failures that Tomcat rejects before a request reaches the application,
 * such as an oversized header or a malformed path, as a ProblemDetail with a KUSS code instead of
 * the Tomcat HTML page. These requests never reach the request filter, so the body carries no
 * requestId.
 */
public class ProblemDetailErrorReportValve extends ErrorReportValve {

  private static final String BODY_TEMPLATE =
      "{\"type\":\"about:blank\",\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\",\""
          + RequestConstants.PROBLEM_CODE
          + "\":\"%s\"}";

  @Override
  protected void report(Request request, Response response, Throwable throwable) {
    int status = response.getStatus();
    if (status < HttpStatus.BAD_REQUEST.value()
        || response.getContentWritten() > 0
        || !response.setErrorReported()) {
      return;
    }
    try {
      Writer writer = response.getReporter();
      if (writer != null) {
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        writer.write(body(HttpStatusCode.valueOf(status)));
        response.finishResponse();
      }
    } catch (IOException | IllegalStateException ex) {
      response.setStatus(status);
    }
  }

  private static String body(HttpStatusCode status) {
    HttpStatus known = HttpStatus.resolve(status.value());
    String title = known == null ? "Error" : known.getReasonPhrase();
    ErrorCode code = codeFor(status);
    String detail =
        switch (code) {
          case KUSS_RESOURCE_NOT_FOUND -> ErrorMessageConstants.RESOURCE_NOT_FOUND;
          case KUSS_INTERNAL_ERROR -> ErrorMessageConstants.UNEXPECTED_ERROR;
          default -> ErrorMessageConstants.INVALID_REQUEST;
        };
    return BODY_TEMPLATE.formatted(title, status.value(), detail, code);
  }

  private static ErrorCode codeFor(HttpStatusCode status) {
    if (status.isSameCodeAs(HttpStatus.NOT_FOUND)) {
      return ErrorCode.KUSS_RESOURCE_NOT_FOUND;
    }
    return status.is5xxServerError()
        ? ErrorCode.KUSS_INTERNAL_ERROR
        : ErrorCode.KUSS_VALIDATION_FAILED;
  }
}
