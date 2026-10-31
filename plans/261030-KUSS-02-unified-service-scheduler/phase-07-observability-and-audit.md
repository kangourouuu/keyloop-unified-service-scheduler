# Phase 07 — Observability and audit

## Context Links
- Plan: [plan.md](./plan.md) · Draft §6.6–6.7 (partitions, idempotency TTL), §7.4 (audit flow), §9 (observability)
- [researcher-01](./research/researcher-01-spring-boot-4-stack.md) §4, §6 · [researcher-02](./research/researcher-02-postgres-concurrency.md) §5, §7 · C-017 #3, #4, #10

## Overview
- Priority: P2 · Status: Pending · Effort: 6h <!-- Updated: Red Team #6 --> · Story: KUSS-04
- Commits: `[KUSS-04] Add async request audit`, `[KUSS-04] Add partition and idempotency maintenance job`, `[KUSS-04] Add booking metrics, probes and structured logging`
- Audit every application request (all paths except `/actuator/**`, `/v3/api-docs/**`, `/swagger-ui/**`) off the critical path; keep partitions + idempotency table trimmed; expose metrics, probes, traces.

## Key Insights
- Audit runs after the business tx closed (filter `finally`) on a bounded pool → never holds two connections, never blocks the response, never starves Hikari.
- `REQUIRES_NEW` on the audit thread = plain new tx; on failure mark rollback-only explicitly (`TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()`), count, WARN.
- We call the executor directly (`@Qualifier("auditExecutor")`), no `@Async`/`@EnableAsync` → nothing to hijack.
- Partition DDL cannot bind identifiers → names built only from `DateTimeFormatter.BASIC_ISO_DATE` or read from `pg_class` with regex `^request_audit_p[0-9]{8}$`. No user input reaches DDL.
- `DROP` never prunes `request_audit_default` → separate `DELETE`. Creating a day already present in DEFAULT fails → log, next day self-heals.
- Metric and tag names live in `common/constants/MetricConstants` (`BOOKING_REQUESTS = "scheduler.booking.requests"`, `BOOKING_GIST_RETRIES`, `AUDIT_WRITES`, `AVAILABILITY_QUERY_DURATION`): registration and the ITs that read them share one name. <!-- Updated: constants decision 2026-10-31 -->
- Micrometer names without `_total` (`scheduler.booking.requests`); Prometheus adds it → `scheduler_booking_requests_total` as in draft §9.
- `service_type` tag must stay bounded without a lookup: request code only for outcomes reached after validation (`confirmed`, `KUSS_NO_CAPACITY`, `KUSS_VEHICLE_ALREADY_BOOKED`, `KUSS_IDEMPOTENCY_IN_FLIGHT`, `KUSS_IDEMPOTENCY_KEY_MISMATCH`), else `unknown`. <!-- Updated: Red Team #13 -->
- Audit scope matched on `request.getServletPath()` (decoded + normalized by the container), never raw `getRequestURI()` (encoded / `;matrix` variants would slip past a prefix check). <!-- Updated: Red Team #10 -->
- All meters pre-registered at startup (every tag value) → tests read sums, never hit a missing meter. <!-- Updated: Red Team #11 -->
- **Developer decision (after review, 2026-10-31):** JdbcClient in this phase is replaced by JPA (Spring Data @Query / entity persist + flush; EntityManager native queries only for partition DDL) — overrides. Reason: one data-access style.

## Requirements
- Audit row per audited request: `id`, `occurred_at` (`clock.instant().truncatedTo(MICROS)`), `request_id`, method (truncated to 10), path (servlet path, truncated to 255), status, `error_code` (request attribute `kuss_error_code`, null on 2xx), `duration_ms`, payload only for `POST /api/v1/appointments` (sanitized to `dealershipId, vehicleId, serviceTypeCode, desiredStartTime`; unparsable → null), null otherwise.
- Executor `auditExecutor`: core 2, max 2, queue 1000, prefix `audit-`; rejection → `scheduler.audit.writes{status=failure}` + WARN logging the dropped `RequestAuditDto` (ids, method, path, status, code; no payload, no PII) so a dropped record leaves a trace <!-- Updated: Red Team #14 -->; wait for tasks on shutdown (5s).
- Maintenance (startup via `SmartLifecycle.start()` with `getPhase() = Integer.MIN_VALUE`, i.e. before the embedded web server starts listening, so no request can land in DEFAULT for today first; plus `@Scheduled(cron = "0 5 0 * * *", zone = "UTC")`): create D..D+7 UTC partitions; drop partitions dated < D−14; delete default rows `occurred_at < D−14`; delete `idempotency_key` rows `created_at < now − 24h`. Every step try/catch + WARN, never throws; all statements idempotent (`IF [NOT] EXISTS`, plain `DELETE`) → safe on N instances.
- Metrics: `scheduler.booking.requests{service_type,result}` (result = `confirmed` for 2xx incl. replay, else KUSS code; DTO-level 400s never reach the service → visible in `http.server.requests` + audit), `scheduler.booking.gist_retries` (phase 05), `scheduler.availability.query.duration` timer, `scheduler.audit.writes{status=success|failure}`.
- Actuator: expose `health,info,prometheus`; probes on; readiness group includes `db`. Tracing via Brave bridge, no exporter → `traceId`/`spanId` in MDC. `prod` profile logs ECS JSON (phase 01). No PII in logs.

## Architecture
```java
// RequestAuditFilter (extends phase 03 version)
protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
    String requestId = startRequest(request, response);          // id, MDC, header, attribute (phase 03)
    boolean isAudited = isAudited(request.getServletPath());      // not /actuator, /v3/api-docs, /swagger-ui
    HttpServletRequest current = isAudited ? new ContentCachingRequestWrapper(request, 4096) : request;
    long startNanos = System.nanoTime();
    try {
        chain.doFilter(current, response);
    } finally {
        if (isAudited) {
            RequestAuditDto audit = toAudit(current, response, requestId, startNanos);
            auditExecutor.execute(() -> requestAuditService.write(audit));
        }
        MDC.remove(MDC_REQUEST_ID);
    }
}
```
```sql
-- RequestAuditServiceImpl.write (@Transactional(propagation = REQUIRES_NEW), JdbcClient) <!-- Superseded: developer decision 2026-10-31, JPA only (see Conventions table) -->
INSERT INTO request_audit (id, occurred_at, request_id, http_method, path, http_status, error_code, duration_ms, request_payload)
VALUES (:id, :occurredAt, :requestId, :httpMethod, :path, :httpStatus, :errorCode, :durationMs, CAST(:payload AS jsonb))
-- MaintenanceServiceImpl
CREATE TABLE IF NOT EXISTS request_audit_p20261031 PARTITION OF request_audit
  FOR VALUES FROM ('2026-10-31 00:00:00+00') TO ('2026-11-01 00:00:00+00');
SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
 WHERE i.inhparent = 'request_audit'::regclass AND c.relname ~ '^request_audit_p[0-9]{8}$';
DROP TABLE IF EXISTS request_audit_p20261016;
DELETE FROM request_audit_default WHERE occurred_at < :cutoff;
DELETE FROM idempotency_key WHERE created_at < :cutoff;
```
`book()` metric wrapper: `try { result = bookWithRetry(...); outcome = "confirmed"; } catch (SchedulerException ex) { outcome = ex.code().name(); throw ex; } finally { counter.increment(); }` (outcome default `KUSS_INTERNAL_ERROR`). Availability: `meterRegistry.timer("scheduler.availability.query.duration").record(() -> …)`.

## Related Code Files
`{root}` = `/Users/kangourouuu/orca/workspaces/keyloop-unified-service-scheduler/tilefish`. Create:
- `{root}/src/main/java/com/keyloop/scheduler/audit/{RequestAuditDto,RequestAuditService,RequestAuditServiceImpl,AuditConfiguration}.java`
- `{root}/src/main/java/com/keyloop/scheduler/audit/{MaintenanceService,MaintenanceServiceImpl}.java`
- `{root}/src/test/java/com/keyloop/scheduler/audit/{RequestAuditIT,PartitionMaintenanceIT}.java`
- `{root}/src/test/java/com/keyloop/scheduler/MetricsIT.java`
Modify:
- `{root}/src/main/java/com/keyloop/scheduler/common/web/RequestAuditFilter.java` (wrapper, timing, dispatch, payload sanitizer)
- `{root}/src/main/java/com/keyloop/scheduler/appointment/AppointmentServiceImpl.java` (requests counter)
- `{root}/src/main/java/com/keyloop/scheduler/availability/AvailabilityServiceImpl.java` (timer)
- `{root}/src/main/resources/application.yml` (`management.endpoints.web.exposure.include`, `management.endpoint.health.probes.enabled`, readiness group `readinessState,db`)

## Implementation Steps
1. Tests first (red) — `RequestAuditIT` (Awaitility ≤ 5s, find row by `X-Request-Id`):
   - POST book 201 with an extra `"note":"x"` field → row 201, `error_code` null, payload has exactly the 4 fields
   - 409 NO_CAPACITY → row with `KUSS_NO_CAPACITY` (business tx rolled back, audit still there)
   - `GET /api/test/boom` → row 500 `KUSS_INTERNAL_ERROR` (§12 #9)
   - malformed JSON POST → row 400 `KUSS_VALIDATION_FAILED`, payload null; availability GET → row, payload null
   - `GET /actuator/health` → no row; `GET /nope` → row 404 `KUSS_RESOURCE_NOT_FOUND`; a 300-char unknown path → row with path truncated to 255
   - audit failure: call `requestAuditService.write(dto)` directly with an invalid jsonb payload (`"{not json"`) → no exception, `scheduler.audit.writes{status=failure}` sum +1 (no DDL on the shared table) <!-- Updated: Red Team #11 -->
2. `PartitionMaintenanceIT`: after startup partitions D..D+7 exist and `request_audit_default` holds no row for today; manual `request_audit_p<D-20>` + `<D-14>` → `runDaily()` drops D−20, keeps D−14; parent insert at D−30 (lands in default) → deleted; `idempotency_key` rows at now−25h deleted, now−1h kept; `runDaily()` twice → no exception, same partition set.
3. `MetricsIT`: booking 201 → `requests{service_type=OIL_CHANGE,result=confirmed}` +1; full slot → `result=KUSS_NO_CAPACITY`; `serviceTypeCode=ZZZ` → `service_type=unknown,result=KUSS_RESOURCE_NOT_FOUND`; availability call → timer count +1; `/actuator/prometheus` contains `scheduler_booking_requests_total` and `scheduler_audit_writes_total`.
   No `ActuatorIT` (framework wiring; Red Team #13): probes and tracing are checked by curl/log in Success Criteria.
4. Implement `AuditConfiguration` executor, `RequestAuditDto`, `RequestAuditService`/`Impl`, filter extension, `MaintenanceService`/`Impl`, metric wiring, actuator config.
5. `./mvnw verify` green; `podman compose up --build` with `SPRING_PROFILES_ACTIVE=prod` → JSON log lines contain `traceId` and `requestId`; `psql` shows audit rows.

## Todo List
- [ ] `RequestAuditIT`, `PartitionMaintenanceIT`, `MetricsIT` red
- [ ] Bounded `auditExecutor` with counted + logged rejection; meters pre-registered
- [ ] `RequestAuditService`/`Impl` (REQUIRES_NEW, catch + WARN + counter)
- [ ] Filter: servlet-path scope, truncation, wrapper, timing, sanitized payload, dispatch
- [ ] `MaintenanceService`/`Impl` (startup + daily, idempotent, logged)
- [ ] Booking requests counter, availability timer, actuator exposure + probes
- [ ] `./mvnw verify` green, compose smoke with prod logs

## Success Criteria
- `./mvnw verify` green: `RequestAuditIT`, `PartitionMaintenanceIT`, `MetricsIT` pass along with all earlier tests.
- `curl -s localhost:8080/actuator/health/liveness` and `/readiness` → `UP`; prod JSON log lines carry `traceId` (if absent, add `spring-boot-micrometer-tracing-brave`).
- `curl -s localhost:8080/actuator/prometheus | grep scheduler_` lists booking, retry, availability and audit meters.

## Risk Assessment
- Startup window closed: partitions are created in a `SmartLifecycle` at phase `Integer.MIN_VALUE`, before the web server lifecycle (`WebServerStartStopLifecycle`) starts; `ApplicationReadyEvent`/`ContextRefreshedEvent` both fire after the server listens (A-023 #1). Daily job keeps D+1..D+7 ahead, so later days never start in DEFAULT. <!-- Updated: Cross-review A-023 #1 -->
- `DROP TABLE` needs ACCESS EXCLUSIVE on the parent → brief block of audit inserts at 00:05 UTC; audit is async, so no request impact.
- Audit executor saturation drops records (counted) — intentional back-pressure, preferred over blocking requests.
- Exception before `write()` body (e.g. no connection) escapes on the audit thread → logged by executor, not counted. Accept.

## Security Considerations
- Payload whitelist (4 fields) → no PII or client junk stored; query strings not stored; path/method truncated to column sizes.
- DDL identifiers derived from dates/catalog only. Logs carry ids, never names/emails/phones.

## Next Steps
- Plan complete → `/plan-story red-team` and `validate`, then `/implement`. Docs (system design, README, AI log) in KUSS-06.
