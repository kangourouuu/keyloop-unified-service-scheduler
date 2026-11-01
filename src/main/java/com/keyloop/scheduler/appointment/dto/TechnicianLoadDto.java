package com.keyloop.scheduler.appointment.dto;

import java.util.UUID;
import lombok.Builder;

@Builder
public record TechnicianLoadDto(UUID technicianId, long bookedMinutes) {}
