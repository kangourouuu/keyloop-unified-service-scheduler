package com.keyloop.scheduler.common.constants;

public final class ErrorMessageConstants {

  public static final String MALFORMED_REQUEST = "Malformed request.";
  public static final String INVALID_REQUEST = "Invalid request.";
  public static final String RESOURCE_NOT_FOUND = "Resource not found.";
  public static final String METHOD_NOT_ALLOWED = "Method not allowed.";
  public static final String IDEMPOTENCY_KEY_MISSING = "Idempotency-Key header is required.";
  public static final String UNEXPECTED_ERROR =
      "Unexpected error. Quote the requestId when reporting.";

  private ErrorMessageConstants() {}
}
