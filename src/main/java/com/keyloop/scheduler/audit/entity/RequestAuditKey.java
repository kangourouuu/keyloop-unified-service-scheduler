package com.keyloop.scheduler.audit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.Instant;
import java.util.UUID;

@Embeddable
public record RequestAuditKey(
    @Column(name = "id") UUID id, @Column(name = "occurred_at") Instant occurredAt) {}
