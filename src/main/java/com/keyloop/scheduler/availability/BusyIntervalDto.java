package com.keyloop.scheduler.availability;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

@Builder
public record BusyIntervalDto(
    UUID serviceBayId, UUID technicianId, Instant startTime, Instant endTime) {}
