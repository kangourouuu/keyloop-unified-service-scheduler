package com.keyloop.scheduler.audit.service;

import com.keyloop.scheduler.audit.dto.RequestAuditDto;

public interface RequestAuditService {

  /**
   * Persists one audit row in its own transaction, after the business transaction has closed. Never
   * throws: a failed write is counted and logged so an audit problem cannot fail the request that
   * produced it.
   */
  void write(RequestAuditDto audit);

  /**
   * Records an audit row that the bounded audit executor refused. The row is counted and logged
   * without its payload so the dropped request still leaves a trace.
   */
  void reject(RequestAuditDto audit);
}
