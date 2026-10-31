package com.keyloop.scheduler.appointment.service;

import com.keyloop.scheduler.appointment.dto.AppointmentDto;
import com.keyloop.scheduler.appointment.dto.AppointmentResultDto;
import com.keyloop.scheduler.appointment.dto.BookAppointmentDto;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface AppointmentService {

  AppointmentResultDto book(BookAppointmentDto request, String idempotencyKey);

  AppointmentResultDto cancel(UUID appointmentId, String idempotencyKey);

  AppointmentDto getAppointment(UUID appointmentId);

  List<AppointmentDto> listForDay(UUID dealershipId, LocalDate date);
}
