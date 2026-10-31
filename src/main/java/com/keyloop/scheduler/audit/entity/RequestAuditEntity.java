package com.keyloop.scheduler.audit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
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
@Table(name = "request_audit")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class RequestAuditEntity implements Persistable<RequestAuditKey> {

  @EmbeddedId private RequestAuditKey key;

  @Column(name = "request_id")
  private String requestId;

  @Column(name = "http_method")
  private String httpMethod;

  @Column(name = "path")
  private String path;

  @Column(name = "http_status")
  private int httpStatus;

  @Column(name = "error_code")
  private String errorCode;

  @Column(name = "duration_ms")
  private int durationMs;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "request_payload")
  private String payload;

  @Transient @Builder.Default private boolean newEntity = true;

  @Override
  public RequestAuditKey getId() {
    return key;
  }

  @Override
  public boolean isNew() {
    return newEntity;
  }

  @PostLoad
  @PostPersist
  void markNotNew() {
    this.newEntity = false;
  }
}
