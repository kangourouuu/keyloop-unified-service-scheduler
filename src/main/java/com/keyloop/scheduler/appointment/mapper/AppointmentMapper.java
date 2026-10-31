package com.keyloop.scheduler.appointment.mapper;

import com.keyloop.scheduler.appointment.dto.AppointmentDto;
import com.keyloop.scheduler.appointment.entity.AppointmentEntity;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface AppointmentMapper {

  AppointmentEntity toEntity(AppointmentDto appointment);

  AppointmentDto toDto(AppointmentEntity appointment);
}
