package com.keyloop.scheduler.appointment.mapper;

import com.keyloop.scheduler.appointment.dto.AppointmentDto;
import com.keyloop.scheduler.appointment.entity.AppointmentEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface AppointmentMapper {

  @Mapping(target = "isNewEntity", ignore = true)
  AppointmentEntity toEntity(AppointmentDto appointment);

  AppointmentDto toDto(AppointmentEntity appointment);
}
