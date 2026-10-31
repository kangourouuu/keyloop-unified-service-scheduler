package com.keyloop.scheduler.audit.service;

public interface MaintenanceService {

  /**
   * Keeps the request audit partitions and the idempotency table trimmed: creates the UTC day
   * partitions for today through seven days ahead, drops partitions older than fourteen days,
   * deletes default-partition audit rows older than fourteen days and idempotency keys older than
   * twenty-four hours. Every step is idempotent and logged on failure, so the job never throws and
   * is safe to run on several instances.
   */
  void runDaily();
}
