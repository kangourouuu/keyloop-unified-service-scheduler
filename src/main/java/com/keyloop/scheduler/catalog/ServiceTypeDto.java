package com.keyloop.scheduler.catalog;

import lombok.Builder;

@Builder
public record ServiceTypeDto(String code, String displayName, Integer durationMinutes) {}
