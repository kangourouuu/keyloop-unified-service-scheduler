package com.keyloop.scheduler.common.error;

public class SchedulerException extends RuntimeException {

  private final ErrorCode code;

  public SchedulerException(ErrorCode code, String detail) {
    super(detail);
    this.code = code;
  }

  public ErrorCode getCode() {
    return code;
  }
}
