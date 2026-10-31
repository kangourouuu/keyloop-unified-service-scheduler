package com.keyloop.scheduler.availability;

import java.time.OffsetDateTime;
import lombok.Builder;

@Builder
public record SlotDto(OffsetDateTime startTime, int availableCapacity) {}
