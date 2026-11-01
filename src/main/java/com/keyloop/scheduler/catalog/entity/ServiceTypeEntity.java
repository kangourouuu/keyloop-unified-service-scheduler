package com.keyloop.scheduler.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "service_type")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ServiceTypeEntity {

  @Id
  @Column(name = "code")
  private String code;

  @Column(name = "display_name")
  private String displayName;

  @Column(name = "duration_minutes")
  private Integer durationMinutes;

  @Column(name = "active")
  private boolean isActive;
}
