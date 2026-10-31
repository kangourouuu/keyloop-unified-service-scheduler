package com.keyloop.scheduler.common.error;

import com.keyloop.scheduler.common.constants.ErrorMessageConstants;
import com.keyloop.scheduler.common.constants.HeaderConstants;
import com.keyloop.scheduler.common.constants.RequestConstants;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@Slf4j
@RestControllerAdvice
class SchedulerGlobalHandler {

  private static final String RETRY_AFTER_SECONDS = "1";

  @ExceptionHandler(SchedulerException.class)
  ResponseEntity<ProblemDetail> handleScheduler(SchedulerException ex, HttpServletRequest request) {
    HttpHeaders headers = new HttpHeaders();
    if (ex.getCode() == ErrorCode.KUSS_SERVICE_BUSY) {
      headers.set(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
    }
    return respond(ex.getCode().status(), ex.getCode(), ex.getMessage(), request, headers);
  }

  @ExceptionHandler(MissingRequestHeaderException.class)
  ResponseEntity<ProblemDetail> handleMissingHeader(
      MissingRequestHeaderException ex, HttpServletRequest request) {
    if (HeaderConstants.IDEMPOTENCY_KEY.equalsIgnoreCase(ex.getHeaderName())) {
      return respond(
          HttpStatus.BAD_REQUEST,
          ErrorCode.KUSS_IDEMPOTENCY_KEY_MISSING,
          ErrorMessageConstants.IDEMPOTENCY_KEY_MISSING,
          request);
    }
    return respond(
        HttpStatus.BAD_REQUEST,
        ErrorCode.KUSS_VALIDATION_FAILED,
        ErrorMessageConstants.INVALID_REQUEST,
        request);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ProblemDetail> handleBodyValidation(
      MethodArgumentNotValidException ex, HttpServletRequest request) {
    String detail =
        ex.getFieldErrors().stream()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .collect(Collectors.joining(", "));
    return respond(HttpStatus.BAD_REQUEST, ErrorCode.KUSS_VALIDATION_FAILED, detail, request);
  }

  @ExceptionHandler(HandlerMethodValidationException.class)
  ResponseEntity<ProblemDetail> handleMethodValidation(
      HandlerMethodValidationException ex, HttpServletRequest request) {
    String detail =
        ex.getParameterValidationResults().stream()
            .flatMap(result ->
                result.getResolvableErrors().stream()
                    .map(error ->
                        result.getMethodParameter().getParameterName()
                            + ": "
                            + error.getDefaultMessage()))
            .collect(Collectors.joining(", "));
    return respond(HttpStatus.BAD_REQUEST, ErrorCode.KUSS_VALIDATION_FAILED, detail, request);
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
    return respond(
        HttpStatus.BAD_REQUEST,
        ErrorCode.KUSS_VALIDATION_FAILED,
        ErrorMessageConstants.MALFORMED_REQUEST,
        request);
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
