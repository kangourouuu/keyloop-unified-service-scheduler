package com.keyloop.scheduler.common.constants;

public final class MetricConstants {

  public static final String BOOKING_REQUESTS = "scheduler.booking.requests";
  public static final String BOOKING_GIST_RETRIES = "scheduler.booking.gist_retries";
  public static final String AUDIT_WRITES = "scheduler.audit.writes";
  public static final String AVAILABILITY_QUERY_DURATION = "scheduler.availability.query.duration";

  public static final String TAG_SERVICE_TYPE = "service_type";
  public static final String TAG_RESULT = "result";
  public static final String TAG_CONFLICT_TYPE = "conflict_type";
  public static final String TAG_STATUS = "status";
  public static final String UNKNOWN_SERVICE_TYPE = "unknown";
  public static final String STATUS_SUCCESS = "success";
  public static final String STATUS_FAILURE = "failure";

  private MetricConstants() {}
}
