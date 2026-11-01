package com.keyloop.scheduler.common.constants;

import java.time.Duration;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RequestConstants {

  public static final String REQUEST_ID_ATTRIBUTE = "kuss_request_id";
  public static final String ERROR_CODE_ATTRIBUTE = "kuss_error_code";
  public static final String PROBLEM_CODE = "code";
  public static final String PROBLEM_REQUEST_ID = "requestId";
  public static final String REQUEST_ID_MDC_KEY = "requestId";
  public static final int TRANSACTION_TIMEOUT_SECONDS = 5;
  public static final Duration IDEMPOTENCY_KEY_TTL = Duration.ofHours(24);
}
