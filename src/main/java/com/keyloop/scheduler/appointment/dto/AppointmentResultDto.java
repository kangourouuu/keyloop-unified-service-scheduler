package com.keyloop.scheduler.appointment.dto;

import lombok.Builder;

@Builder
public record AppointmentResultDto(int httpStatus, AppointmentDto appointment) {}
