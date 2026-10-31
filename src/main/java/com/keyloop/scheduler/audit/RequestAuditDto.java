package com.keyloop.scheduler.audit;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

@Builder
public record RequestAuditDto(
    UUID id,
    Instant occurredAt,
    String requestId,
    String httpMethod,
    String path,
    int httpStatus,
    String errorCode,
    int durationMs,
    String payload) {}
