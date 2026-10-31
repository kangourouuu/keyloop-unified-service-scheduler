package com.keyloop.scheduler.audit.service.impl;

import static com.keyloop.scheduler.common.constants.MetricConstants.AUDIT_WRITES;
import static com.keyloop.scheduler.common.constants.MetricConstants.STATUS_FAILURE;
import static com.keyloop.scheduler.common.constants.MetricConstants.STATUS_SUCCESS;
import static com.keyloop.scheduler.common.constants.MetricConstants.TAG_STATUS;

import com.keyloop.scheduler.audit.dto.RequestAuditDto;
import com.keyloop.scheduler.audit.mapper.RequestAuditMapper;
import com.keyloop.scheduler.audit.repository.RequestAuditRepository;
import com.keyloop.scheduler.audit.service.RequestAuditService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

@Slf4j
@Service
class RequestAuditServiceImpl implements RequestAuditService {

  private final RequestAuditRepository requestAuditRepository;
  private final RequestAuditMapper requestAuditMapper;
  private final Counter successes;
  private final Counter failures;

  RequestAuditServiceImpl(
      RequestAuditRepository requestAuditRepository,
      RequestAuditMapper requestAuditMapper,
      MeterRegistry meterRegistry) {
    this.requestAuditRepository = requestAuditRepository;
    this.requestAuditMapper = requestAuditMapper;
    this.successes = meterRegistry.counter(AUDIT_WRITES, TAG_STATUS, STATUS_SUCCESS);
    this.failures = meterRegistry.counter(AUDIT_WRITES, TAG_STATUS, STATUS_FAILURE);
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void write(RequestAuditDto audit) {
    try {
      requestAuditRepository.saveAndFlush(requestAuditMapper.toEntity(audit));
      successes.increment();
    } catch (RuntimeException ex) {
      TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
      failures.increment();
      log.warn(
          "Request audit write failed requestId={} path={} status={}",
          audit.requestId(),
          audit.path(),
          audit.httpStatus(),
          ex);
    }
  }

  @Override
  public void reject(RequestAuditDto audit) {
    failures.increment();
    log.warn(
        "Request audit dropped requestId={} method={} path={} status={} errorCode={}",
        audit.requestId(),
        audit.httpMethod(),
        audit.path(),
        audit.httpStatus(),
        audit.errorCode());
  }
}
