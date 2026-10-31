package com.keyloop.scheduler.appointment.mapper;

import com.keyloop.scheduler.appointment.dto.AppointmentDto;
import com.keyloop.scheduler.appointment.dto.AppointmentResultDto;
import com.keyloop.scheduler.appointment.entity.IdempotencyKeyEntity;
import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import tools.jackson.databind.json.JsonMapper;

@Mapper(componentModel = "spring")
public interface IdempotencyKeyMapper {

  @Mapping(target = "httpStatus", source = "responseStatus")
  @Mapping(target = "appointment", source = "responseBody")
  AppointmentResultDto toResult(IdempotencyKeyEntity stored, @Context JsonMapper jsonMapper);

  default AppointmentDto toAppointment(String responseBody, @Context JsonMapper jsonMapper) {
    return jsonMapper.readValue(responseBody, AppointmentDto.class);
  }

  default String toResponseBody(AppointmentDto appointment, @Context JsonMapper jsonMapper) {
    return jsonMapper.writeValueAsString(appointment);
  }
}
