package com.keyloop.scheduler.common.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
  KUSS_VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
  KUSS_IDEMPOTENCY_KEY_MISSING(HttpStatus.BAD_REQUEST),
  KUSS_RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND),
  KUSS_VEHICLE_ALREADY_BOOKED(HttpStatus.CONFLICT),
  KUSS_NO_CAPACITY(HttpStatus.CONFLICT),
  KUSS_IDEMPOTENCY_IN_FLIGHT(HttpStatus.CONFLICT),
  KUSS_APPOINTMENT_ALREADY_STARTED(HttpStatus.CONFLICT),
  KUSS_IDEMPOTENCY_KEY_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT),
  KUSS_INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),
  KUSS_SERVICE_BUSY(HttpStatus.SERVICE_UNAVAILABLE);

  private final HttpStatus status;

  ErrorCode(HttpStatus status) {
    this.status = status;
  }

  public HttpStatus status() {
    return status;
  }
}
