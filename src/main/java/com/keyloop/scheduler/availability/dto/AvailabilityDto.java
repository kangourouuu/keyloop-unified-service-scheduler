package com.keyloop.scheduler.availability.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.Builder;

@Builder
public record AvailabilityDto(
    UUID dealershipId,
    LocalDate date,
    String serviceTypeCode,
    String timezone,
    List<SlotDto> slots) {}
