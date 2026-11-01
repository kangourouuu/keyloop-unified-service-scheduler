package com.keyloop.scheduler.appointment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Builder;

@Builder
public record BookAppointmentDto(
    @NotNull UUID dealershipId,
    @NotNull UUID vehicleId,
    @NotBlank @Size(max = 40) String serviceTypeCode,
    @NotNull OffsetDateTime desiredStartTime) {}
