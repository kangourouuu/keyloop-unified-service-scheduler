package com.keyloop.scheduler.audit.mapper;

import com.keyloop.scheduler.audit.dto.RequestAuditDto;
import com.keyloop.scheduler.audit.entity.RequestAuditEntity;
import com.keyloop.scheduler.audit.entity.RequestAuditKey;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring", imports = RequestAuditKey.class)
public interface RequestAuditMapper {

  @Mapping(
      target = "key",
      expression =
          "java(RequestAuditKey.builder().id(audit.id()).occurredAt(audit.occurredAt()).build())")
  @Mapping(target = "isNewEntity", ignore = true)
  RequestAuditEntity toEntity(RequestAuditDto audit);
}
