package com.keyloop.scheduler.catalog;

import java.util.Set;
import java.util.UUID;
import lombok.Builder;

@Builder
public record TechnicianDto(UUID id, String fullName, Boolean active, Set<String> skillCodes) {}
