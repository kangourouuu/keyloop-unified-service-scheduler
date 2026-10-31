package com.keyloop.scheduler.availability.mapper;

import com.keyloop.scheduler.appointment.entity.AppointmentEntity;
import com.keyloop.scheduler.availability.dto.BusyIntervalDto;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface AvailabilityMapper {

  BusyIntervalDto toBusyInterval(AppointmentEntity appointment);
}
