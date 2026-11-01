package com.keyloop.scheduler.appointment.util;

import com.keyloop.scheduler.common.constants.ErrorMessageConstants;
import com.keyloop.scheduler.common.error.ErrorCode;
import com.keyloop.scheduler.common.error.SchedulerException;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class BookingErrorUtils {

  public static SchedulerException bookingNotCompleted() {
    return new SchedulerException(
        ErrorCode.KUSS_INTERNAL_ERROR, ErrorMessageConstants.BOOKING_NOT_COMPLETED);
  }

  public static SchedulerException serviceBusy() {
    return new SchedulerException(ErrorCode.KUSS_SERVICE_BUSY, ErrorMessageConstants.SERVICE_BUSY);
  }

  public static SchedulerException deadlineReached() {
    log.warn("Booking deadline reached, giving up as service busy");
    return serviceBusy();
  }

  public static SchedulerException vehicleAlreadyBooked() {
    return new SchedulerException(
        ErrorCode.KUSS_VEHICLE_ALREADY_BOOKED, ErrorMessageConstants.VEHICLE_ALREADY_BOOKED);
  }

  public static SchedulerException appointmentNotFound() {
    return new SchedulerException(
        ErrorCode.KUSS_RESOURCE_NOT_FOUND, ErrorMessageConstants.APPOINTMENT_NOT_FOUND);
  }
}
