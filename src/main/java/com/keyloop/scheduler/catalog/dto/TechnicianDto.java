package com.keyloop.scheduler.catalog.dto;

import java.util.Set;
import java.util.UUID;
import lombok.Builder;

@Builder
public record TechnicianDto(UUID id, String fullName, boolean isActive, Set<String> skillCodes) {}
