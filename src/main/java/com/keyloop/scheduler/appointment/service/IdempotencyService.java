package com.keyloop.scheduler.appointment.service;

import com.keyloop.scheduler.appointment.dto.AppointmentResultDto;
import java.util.Optional;

public interface IdempotencyService {

  Optional<AppointmentResultDto> claim(String key, String canonicalRequest);

  void complete(String key, AppointmentResultDto result);
}
