package com.keyloop.scheduler.catalog;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "technician")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
class TechnicianEntity {

  @Id
  @Column(name = "id")
  private UUID id;

  @Column(name = "dealership_id")
  private UUID dealershipId;

  @Column(name = "full_name")
  private String fullName;

  @Column(name = "active")
  private Boolean active;

  @ElementCollection(fetch = FetchType.LAZY)
  @CollectionTable(name = "technician_skill", joinColumns = @JoinColumn(name = "technician_id"))
  @Column(name = "service_type_code")
  @Builder.Default
  private Set<String> skillCodes = new HashSet<>();
}
