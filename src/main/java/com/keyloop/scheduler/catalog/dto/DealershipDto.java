package com.keyloop.scheduler.catalog.dto;

import java.time.LocalTime;
import java.util.UUID;
import lombok.Builder;

@Builder
public record DealershipDto(
    UUID id, String name, String timezone, LocalTime openTime, LocalTime closeTime) {}
