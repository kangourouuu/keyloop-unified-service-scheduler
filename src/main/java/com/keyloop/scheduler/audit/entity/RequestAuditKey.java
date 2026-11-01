package com.keyloop.scheduler.audit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

@Embeddable
@Builder
public record RequestAuditKey(
    @Column(name = "id") UUID id, @Column(name = "occurred_at") Instant occurredAt) {}
