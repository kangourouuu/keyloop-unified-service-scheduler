package com.keyloop.scheduler.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "dealership")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
class DealershipEntity {

  @Id
  @Column(name = "id")
  private UUID id;

  @Column(name = "name")
  private String name;

  @Column(name = "timezone")
  private String timezone;

  @Column(name = "open_time")
  private LocalTime openTime;

  @Column(name = "close_time")
  private LocalTime closeTime;
}
