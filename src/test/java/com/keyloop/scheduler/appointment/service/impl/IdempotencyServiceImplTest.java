package com.keyloop.scheduler.appointment.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.keyloop.scheduler.appointment.dto.AppointmentDto;
import com.keyloop.scheduler.appointment.dto.AppointmentResultDto;
import com.keyloop.scheduler.appointment.mapper.IdempotencyKeyMapper;
import com.keyloop.scheduler.appointment.repository.IdempotencyKeyRepository;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import tools.jackson.databind.json.JsonMapper;

class IdempotencyServiceImplTest {

  @Test
  void should_fail_when_no_open_claim_matches_the_key_on_completion() {
    IdempotencyKeyRepository repository = mock(IdempotencyKeyRepository.class);
    when(repository.complete(anyString(), anyInt(), any())).thenReturn(0);
    IdempotencyServiceImpl service =
        new IdempotencyServiceImpl(
            repository,
            Mappers.getMapper(IdempotencyKeyMapper.class),
            JsonMapper.builder().build(),
            Clock.systemUTC());
    AppointmentResultDto result =
        AppointmentResultDto.builder()
            .httpStatus(201)
            .appointment(AppointmentDto.builder().build())
            .build();

    assertThatThrownBy(() -> service.complete("k-missing", result))
        .isInstanceOf(IllegalStateException.class);
  }
}
