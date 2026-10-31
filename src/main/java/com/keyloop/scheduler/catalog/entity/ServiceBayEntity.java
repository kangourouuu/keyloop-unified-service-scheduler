package com.keyloop.scheduler.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "service_bay")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ServiceBayEntity {

  @Id
  @Column(name = "id")
  private UUID id;

  @Column(name = "dealership_id")
  private UUID dealershipId;

  @Column(name = "bay_code")
  private String bayCode;

  @Column(name = "active")
  private Boolean active;
}
