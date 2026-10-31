package com.keyloop.scheduler.common.constants;

public final class ErrorMessageConstants {

  public static final String MALFORMED_REQUEST = "Malformed request.";
  public static final String INVALID_REQUEST = "Invalid request.";
  public static final String RESOURCE_NOT_FOUND = "Resource not found.";
  public static final String METHOD_NOT_ALLOWED = "Method not allowed.";
  public static final String IDEMPOTENCY_KEY_MISSING = "Idempotency-Key header is required.";
  public static final String UNEXPECTED_ERROR =
      "Unexpected error. Quote the requestId when reporting.";

  public static final String DEALERSHIP_NOT_FOUND = "Dealership not found.";
  public static final String SERVICE_TYPE_NOT_FOUND = "Service type not found.";
  public static final String DATE_OUT_OF_HORIZON = "Date is more than 90 days ahead.";

  public static final String VEHICLE_NOT_FOUND = "Vehicle not found.";
  public static final String SLOT_NOT_BOOKABLE = "Start time is not a bookable slot.";
  public static final String VEHICLE_ALREADY_BOOKED =
      "Vehicle already has an overlapping appointment.";
  public static final String NO_CAPACITY = "No bay and technician are free for this slot.";
  public static final String IDEMPOTENCY_IN_FLIGHT =
      "A request with this Idempotency-Key is still in progress.";
  public static final String IDEMPOTENCY_KEY_MISMATCH =
      "Idempotency-Key was already used with a different request.";
  public static final String BOOKING_NOT_COMPLETED = "Booking could not be completed.";

  public static final String APPOINTMENT_NOT_FOUND = "Appointment not found.";
  public static final String APPOINTMENT_ALREADY_STARTED = "Appointment has already started.";

  private ErrorMessageConstants() {}
}
