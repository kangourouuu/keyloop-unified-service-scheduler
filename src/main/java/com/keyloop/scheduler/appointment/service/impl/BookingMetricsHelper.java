package com.keyloop.scheduler.appointment.service.impl;

import com.keyloop.scheduler.appointment.dto.BookAppointmentDto;
import com.keyloop.scheduler.catalog.dto.ServiceTypeDto;
import com.keyloop.scheduler.catalog.service.CatalogService;
import com.keyloop.scheduler.common.constants.MetricConstants;
import com.keyloop.scheduler.common.error.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

@Component
class BookingMetricsHelper {

  private static final Set<String> BOOKING_RESULTS =
      Set.of(
          MetricConstants.RESULT_CONFIRMED,
          ErrorCode.KUSS_VALIDATION_FAILED.name(),
          ErrorCode.KUSS_IDEMPOTENCY_KEY_MISSING.name(),
          ErrorCode.KUSS_RESOURCE_NOT_FOUND.name(),
          ErrorCode.KUSS_INTERNAL_ERROR.name(),
          ErrorCode.KUSS_SERVICE_BUSY.name(),
          ErrorCode.KUSS_NO_CAPACITY.name(),
          ErrorCode.KUSS_VEHICLE_ALREADY_BOOKED.name(),
          ErrorCode.KUSS_IDEMPOTENCY_IN_FLIGHT.name(),
          ErrorCode.KUSS_IDEMPOTENCY_KEY_MISMATCH.name());
  private static final Set<String> TRACKED_SERVICE_TYPE_RESULTS =
      Set.of(
          MetricConstants.RESULT_CONFIRMED,
          ErrorCode.KUSS_NO_CAPACITY.name(),
          ErrorCode.KUSS_VEHICLE_ALREADY_BOOKED.name());

  private final CatalogService catalogService;
  private final MeterRegistry meterRegistry;
  private final Map<String, Counter> retryCounters;

  BookingMetricsHelper(CatalogService catalogService, MeterRegistry meterRegistry) {
    this.catalogService = catalogService;
    this.meterRegistry = meterRegistry;
    this.retryCounters =
        Stream.of(
                MetricConstants.CONFLICT_BAY,
                MetricConstants.CONFLICT_TECHNICIAN,
                MetricConstants.CONFLICT_DEADLOCK)
            .collect(
                Collectors.toMap(
                    Function.identity(),
                    conflictType ->
                        Counter.builder(MetricConstants.BOOKING_GIST_RETRIES)
                            .tag(MetricConstants.TAG_CONFLICT_TYPE, conflictType)
                            .register(meterRegistry)));
  }

  /**
   * Counts one finished booking request. Only confirmed, no-capacity and vehicle-conflict results
   * carry the requested service type, which the request has already been validated against; every
   * other result is tagged unknown so a client-chosen code cannot create meters.
   */
  void recordBooking(BookAppointmentDto request, String result) {
    String serviceType =
        TRACKED_SERVICE_TYPE_RESULTS.contains(result)
            ? request.serviceTypeCode()
            : MetricConstants.UNKNOWN_SERVICE_TYPE;
    bookingCounter(serviceType, result).increment();
  }

  void countRetry(String conflictType) {
    retryCounters.get(conflictType).increment();
  }

  @PostConstruct
  void registerBookingMeters() {
    List<String> serviceTypeCodes =
        catalogService.findServiceTypes().stream().map(ServiceTypeDto::code).toList();
    BOOKING_RESULTS.forEach(result -> {
      bookingCounter(MetricConstants.UNKNOWN_SERVICE_TYPE, result);
      if (TRACKED_SERVICE_TYPE_RESULTS.contains(result)) {
        serviceTypeCodes.forEach(code -> bookingCounter(code, result));
      }
    });
  }

  private Counter bookingCounter(String serviceType, String result) {
    return meterRegistry.counter(
        MetricConstants.BOOKING_REQUESTS,
        MetricConstants.TAG_SERVICE_TYPE,
        serviceType,
        MetricConstants.TAG_RESULT,
        result);
  }
}
