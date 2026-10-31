package com.keyloop.scheduler.catalog.dto;

import java.util.List;
import java.util.UUID;
import lombok.Builder;

@Builder
public record CustomerDto(UUID id, String fullName, List<VehicleDto> vehicles) {}
