package com.keyloop.scheduler.appointment.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keyloop.scheduler.appointment.dto.BookAppointmentDto;
import com.keyloop.scheduler.appointment.mapper.AppointmentMapper;
import com.keyloop.scheduler.appointment.repository.AppointmentRepository;
import com.keyloop.scheduler.appointment.repository.AppointmentWriteRepository;
import com.keyloop.scheduler.appointment.service.IdempotencyService;
import com.keyloop.scheduler.catalog.repository.DealershipRepository;
import com.keyloop.scheduler.catalog.repository.ServiceTypeRepository;
import com.keyloop.scheduler.catalog.repository.VehicleRepository;
import com.keyloop.scheduler.common.error.ErrorCode;
import com.keyloop.scheduler.common.error.SchedulerException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;

class AppointmentServiceImplTest {

  private static final Instant START = Instant.parse("2026-10-31T10:00:00Z");

  private final PlatformTransactionManager transactionManager =
      mock(PlatformTransactionManager.class);

  @Test
  void should_return_service_busy_when_no_database_connection_is_free() {
    when(transactionManager.getTransaction(any()))
        .thenThrow(new CannotCreateTransactionException("pool exhausted"));
    Clock clock = mock(Clock.class);
    when(clock.instant()).thenReturn(START);

    assertServiceBusy(service(clock));
  }

  @Test
  void should_return_service_busy_without_an_attempt_once_the_deadline_has_passed() {
    Clock clock = mock(Clock.class);
    when(clock.instant()).thenReturn(START, START.plusSeconds(10));

    assertServiceBusy(service(clock));
    verify(transactionManager, never()).getTransaction(any());
  }

  private static void assertServiceBusy(AppointmentServiceImpl service) {
    assertThatThrownBy(() -> service.book(request(), "k-busy"))
        .isInstanceOfSatisfying(
            SchedulerException.class,
            ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.KUSS_SERVICE_BUSY));
  }

  private AppointmentServiceImpl service(Clock clock) {
    return new AppointmentServiceImpl(
        clock,
        mock(DealershipRepository.class),
        mock(VehicleRepository.class),
        mock(ServiceTypeRepository.class),
        mock(AppointmentRepository.class),
        mock(AppointmentWriteRepository.class),
        mock(AppointmentMapper.class),
        mock(IdempotencyService.class),
        transactionManager,
        new SimpleMeterRegistry());
  }

  private static BookAppointmentDto request() {
    return BookAppointmentDto.builder()
        .dealershipId(UUID.randomUUID())
        .vehicleId(UUID.randomUUID())
        .serviceTypeCode("OIL_CHANGE")
        .desiredStartTime(OffsetDateTime.parse("2026-11-07T10:00:00Z"))
        .build();
  }
}
