package com.keyloop.scheduler.appointment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "idempotency_key")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class IdempotencyKeyEntity implements Persistable<String> {

  @Id
  @Column(name = "idempotency_key")
  private String key;

  @Column(name = "request_hash")
  private String requestHash;

  @Column(name = "response_status")
  private Integer responseStatus;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "response_body")
  private String responseBody;

  @Column(name = "created_at")
  private Instant createdAt;

  @Transient @Builder.Default private boolean isNewEntity = true;

  @Override
  public String getId() {
    return key;
  }

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
