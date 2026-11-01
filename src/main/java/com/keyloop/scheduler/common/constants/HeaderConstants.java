package com.keyloop.scheduler.common.constants;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class HeaderConstants {

  public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
  public static final String REQUEST_ID = "X-Request-Id";
}
