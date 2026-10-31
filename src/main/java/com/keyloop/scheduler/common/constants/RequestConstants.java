package com.keyloop.scheduler.common.constants;

import java.time.Duration;

public final class RequestConstants {

  public static final String REQUEST_ID_ATTRIBUTE = "kuss_request_id";
  public static final String ERROR_CODE_ATTRIBUTE = "kuss_error_code";
  public static final String REQUEST_ID_MDC_KEY = "requestId";
  public static final Duration IDEMPOTENCY_KEY_TTL = Duration.ofHours(24);

  private RequestConstants() {}
}
