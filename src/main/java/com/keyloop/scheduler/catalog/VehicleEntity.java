package com.keyloop.scheduler.catalog;

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
@Table(name = "vehicle")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class VehicleEntity {

  @Id
  @Column(name = "id")
  private UUID id;

  @Column(name = "customer_id")
  private UUID customerId;

  @Column(name = "vin")
  private String vin;

  @Column(name = "license_plate")
  private String licensePlate;

  @Column(name = "make")
  private String make;

  @Column(name = "model")
  private String model;

  @Column(name = "model_year")
  private Integer modelYear;
}
