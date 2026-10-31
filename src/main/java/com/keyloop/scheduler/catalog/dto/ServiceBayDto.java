package com.keyloop.scheduler.catalog.dto;

import java.util.UUID;
import lombok.Builder;

@Builder
public record ServiceBayDto(UUID id, String bayCode, Boolean active) {}
