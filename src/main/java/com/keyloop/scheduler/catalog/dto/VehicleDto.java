package com.keyloop.scheduler.catalog.dto;

import java.util.UUID;
import lombok.Builder;

@Builder
public record VehicleDto(UUID id, String make, String model, Integer modelYear) {}
