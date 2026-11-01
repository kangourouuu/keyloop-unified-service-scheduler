package com.keyloop.scheduler.appointment.dto;

import com.keyloop.scheduler.appointment.entity.AppointmentStatus;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

@Builder
public record AppointmentDto(
    UUID id,
    UUID dealershipId,
    UUID customerId,
    UUID vehicleId,
    UUID serviceBayId,
    UUID technicianId,
    String serviceTypeCode,
    Instant startTime,
    Instant endTime,
    AppointmentStatus status,
    Instant createdAt,
    Instant cancelledAt) {}
