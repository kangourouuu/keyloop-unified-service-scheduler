package com.keyloop.scheduler.availability;

import com.keyloop.scheduler.appointment.AppointmentEntity;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
interface AvailabilityMapper {

  BusyIntervalDto toBusyInterval(AppointmentEntity appointment);
}
