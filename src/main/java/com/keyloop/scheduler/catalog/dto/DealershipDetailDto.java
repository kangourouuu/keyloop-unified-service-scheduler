package com.keyloop.scheduler.catalog.dto;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import lombok.Builder;

@Builder
public record DealershipDetailDto(
    UUID id,
    String name,
    String timezone,
    LocalTime openTime,
    LocalTime closeTime,
    List<ServiceBayDto> bays,
    List<TechnicianDto> technicians) {}
