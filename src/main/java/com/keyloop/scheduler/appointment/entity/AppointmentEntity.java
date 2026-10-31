package com.keyloop.scheduler.appointment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "appointment")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AppointmentEntity implements Persistable<UUID> {

  @Id
  @Column(name = "id")
  private UUID id;

  @Column(name = "dealership_id")
  private UUID dealershipId;

  @Column(name = "customer_id")
  private UUID customerId;

  @Column(name = "vehicle_id")
  private UUID vehicleId;

  @Column(name = "service_bay_id")
  private UUID serviceBayId;

  @Column(name = "technician_id")
  private UUID technicianId;

  @Column(name = "service_type_code")
  private String serviceTypeCode;

  @Column(name = "start_time")
  private Instant startTime;

  @Column(name = "end_time")
  private Instant endTime;

  @Enumerated(EnumType.STRING)
  @Column(name = "status")
  private AppointmentStatus status;

  @Column(name = "created_at")
  private Instant createdAt;

  @Column(name = "cancelled_at")
  private Instant cancelledAt;

  @Transient @Builder.Default private boolean isNewEntity = true;

  @Override
  public boolean isNew() {
    return isNewEntity;
  }

  @PostLoad
  @PostPersist
  void markNotNew() {
    this.isNewEntity = false;
  }
}
