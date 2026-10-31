package com.keyloop.scheduler.common.error;

import com.keyloop.scheduler.common.constants.ApiPathConstants;
import com.keyloop.scheduler.common.constants.ErrorMessageConstants;
import com.keyloop.scheduler.common.constants.HeaderConstants;
import com.keyloop.scheduler.common.constants.MetricConstants;
import com.keyloop.scheduler.common.constants.RequestConstants;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
class SchedulerGlobalHandler {

  private static final String RETRY_AFTER_SECONDS = "1";

  private final MeterRegistry meterRegistry;

  @ExceptionHandler(SchedulerException.class)
  ResponseEntity<ProblemDetail> handleScheduler(SchedulerException ex, HttpServletRequest request) {
    HttpHeaders headers = new HttpHeaders();
    if (ex.getCode() == ErrorCode.KUSS_SERVICE_BUSY) {
      headers.set(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
    }
    return respond(ex.getCode().status(), ex.getCode(), ex.getMessage(), request, headers);
  }

  @ExceptionHandler({CannotCreateTransactionException.class, QueryTimeoutException.class})
  ResponseEntity<ProblemDetail> handleOverload(Exception ex, HttpServletRequest request) {
    log.warn(
        "Service busy requestId={}: {}",
        request.getAttribute(RequestConstants.REQUEST_ID_ATTRIBUTE),
        ex.getMessage());
    return handleScheduler(
        new SchedulerException(ErrorCode.KUSS_SERVICE_BUSY, ErrorMessageConstants.SERVICE_BUSY),
        request);
  }

  @ExceptionHandler(MissingRequestHeaderException.class)
  ResponseEntity<ProblemDetail> handleMissingHeader(
      MissingRequestHeaderException ex, HttpServletRequest request) {
    if (HeaderConstants.IDEMPOTENCY_KEY.equalsIgnoreCase(ex.getHeaderName())) {
      return reject(
          ErrorCode.KUSS_IDEMPOTENCY_KEY_MISSING,
          ErrorMessageConstants.IDEMPOTENCY_KEY_MISSING,
          request);
    }
    return reject(ErrorCode.KUSS_VALIDATION_FAILED, ErrorMessageConstants.INVALID_REQUEST, request);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ProblemDetail> handleBodyValidation(
      MethodArgumentNotValidException ex, HttpServletRequest request) {
    String detail =
        ex.getFieldErrors().stream()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .collect(Collectors.joining(", "));
    return reject(ErrorCode.KUSS_VALIDATION_FAILED, detail, request);
  }

  @ExceptionHandler(HandlerMethodValidationException.class)
  ResponseEntity<ProblemDetail> handleMethodValidation(
      HandlerMethodValidationException ex, HttpServletRequest request) {
    String detail =
        ex.getParameterValidationResults().stream()
            .flatMap(SchedulerGlobalHandler::validationMessages)
            .distinct()
            .collect(Collectors.joining(", "));
    return reject(ErrorCode.KUSS_VALIDATION_FAILED, detail, request);
  }

  private static Stream<String> validationMessages(ParameterValidationResult result) {
    if (result instanceof ParameterErrors errors) {
      return errors.getFieldErrors().stream()
          .map(error -> error.getField() + ": " + error.getDefaultMessage());
    }
    String name = result.getMethodParameter().getParameterName();
    return result.getResolvableErrors().stream()
        .map(error -> name + ": " + error.getDefaultMessage());
  }

  @ExceptionHandler(ConstraintViolationException.class)
  ResponseEntity<ProblemDetail> handleConstraintViolation(
      ConstraintViolationException ex, HttpServletRequest request) {
    String detail =
        ex.getConstraintViolations().stream()
            .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
            .collect(Collectors.joining(", "));
    return respond(HttpStatus.BAD_REQUEST, ErrorCode.KUSS_VALIDATION_FAILED, detail, request);
  }

  @ExceptionHandler({HttpMessageNotReadableException.class, TypeMismatchException.class})
  ResponseEntity<ProblemDetail> handleMalformed(Exception ex, HttpServletRequest request) {
    return reject(
        ErrorCode.KUSS_VALIDATION_FAILED, ErrorMessageConstants.MALFORMED_REQUEST, request);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
    if (ex instanceof ErrorResponse errorResponse) {
      return handleErrorResponse(errorResponse, request);
    }
    log.error(
        "Unhandled error requestId={}",
        request.getAttribute(RequestConstants.REQUEST_ID_ATTRIBUTE),
        ex);
    return respond(
        HttpStatus.INTERNAL_SERVER_ERROR,
        ErrorCode.KUSS_INTERNAL_ERROR,
        ErrorMessageConstants.UNEXPECTED_ERROR,
        request);
  }

  private ResponseEntity<ProblemDetail> reject(
      ErrorCode code, String detail, HttpServletRequest request) {
    if (HttpMethod.POST.matches(request.getMethod())
        && ApiPathConstants.APPOINTMENTS.equals(request.getRequestURI())) {
      meterRegistry
          .counter(
              MetricConstants.BOOKING_REQUESTS,
              MetricConstants.TAG_SERVICE_TYPE,
              MetricConstants.UNKNOWN_SERVICE_TYPE,
              MetricConstants.TAG_RESULT,
              code.name())
          .increment();
    }
    return respond(HttpStatus.BAD_REQUEST, code, detail, request);
  }

  private static ResponseEntity<ProblemDetail> handleErrorResponse(
      ErrorResponse error, HttpServletRequest request) {
    HttpStatusCode status = error.getStatusCode();
    ErrorCode code =
        status.isSameCodeAs(HttpStatus.NOT_FOUND)
            ? ErrorCode.KUSS_RESOURCE_NOT_FOUND
            : ErrorCode.KUSS_VALIDATION_FAILED;
    return respond(status, code, detailFor(status), request, error.getHeaders());
  }

  private static String detailFor(HttpStatusCode status) {
    if (status.isSameCodeAs(HttpStatus.NOT_FOUND)) {
      return ErrorMessageConstants.RESOURCE_NOT_FOUND;
    }
    if (status.isSameCodeAs(HttpStatus.METHOD_NOT_ALLOWED)) {
      return ErrorMessageConstants.METHOD_NOT_ALLOWED;
    }
    return ErrorMessageConstants.INVALID_REQUEST;
  }

  private static ResponseEntity<ProblemDetail> respond(
      HttpStatusCode status, ErrorCode code, String detail, HttpServletRequest request) {
    return respond(status, code, detail, request, new HttpHeaders());
  }

  private static ResponseEntity<ProblemDetail> respond(
      HttpStatusCode status,
      ErrorCode code,
      String detail,
      HttpServletRequest request,
      HttpHeaders headers) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setProperty("code", code.name());
    problem.setProperty("requestId", request.getAttribute(RequestConstants.REQUEST_ID_ATTRIBUTE));
    request.setAttribute(RequestConstants.ERROR_CODE_ATTRIBUTE, code.name());
    return ResponseEntity.status(status).headers(headers).body(problem);
  }
}
