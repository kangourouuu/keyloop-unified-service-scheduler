# Red Team — Scope & Complexity Critic (2026-10-30)

Plan: `plans/261030-KUSS-02-unified-service-scheduler` (implementation story KUSS-04). Fixed decisions in draft §12 were left alone (Approach A, Idempotency-Key on book and cancel, the async partitioned `request_audit`, the KUSS_* handler, the extra read endpoints plus cancel, the §9 metrics, and the `*Service`/`*ServiceImpl` pair). Every finding below is about work built on top of those decisions.

## Finding 1: Booking and idempotency scenarios are tested twice, at service level (phase 05) and over HTTP (phase 06)
- **Severity:** High
- **Location:** Phase 05, "Implementation Steps" 2–4; Phase 06, "Implementation Steps" 1; plan.md "Test map"
- **Flaw:** Six §12 scenarios (#4, #5, #6, #8, #10, #11) and "cancel frees capacity" each get a full service-level IT in phase 05 and a second HTTP IT in phase 06. That means two burst harnesses (N=20 service-level and N=10 HTTP, the HTTP one on its own JDK `HttpClient` pool) and two runs of the lock-holder `pg_stat_activity` in-flight test. The HTTP tests already exercise the service code plus the exception-to-KUSS mapping, so the service-level copies prove nothing extra.
- **Failure scenario:** About 2h of phase 05/06 goes on writing the same assertions twice. Each copy has its own concurrency setup, so there are two sources of flakes. Any later change to the error contract has to be fixed in both places. Running `BookingConcurrencyIT` five times as a gate (phase 05 step 6) does not cover the HTTP burst, which has extra failure modes (client pool, server threads).
- **Evidence:** Test map rows: `4 Vehicle double-booking 409 | VehicleOverlapIT, AppointmentApiIT`, `6 In-flight key 409 | IdempotencyIT, AppointmentApiIT`, `10 Idempotent replay | IdempotencyIT, AppointmentApiIT`, `11 Burst N vs K | BookingConcurrencyIT (N=20), AppointmentApiIT (HTTP N=10,K=3)`, `Cancel frees capacity | BookingConcurrencyIT (JDBC cancel), AppointmentApiIT (HTTP)`.
- **Suggested fix:** Use one level per scenario. §12 #11 says "N parallel POSTs" and development-rules says "N parallel requests", so keep a single HTTP `BookingConcurrencyIT` with bay-bound and tech-bound variants plus the DB invariants, and remove the HTTP burst from `AppointmentApiIT`. Put the vehicle, replay, mismatch and in-flight cases only in `AppointmentApiIT`, and delete `VehicleOverlapIT` and `IdempotencyIT`. Keep only what HTTP cannot reach: the "gist_retries unchanged on vehicle conflict" check and the holder-rollback variant can stay as two methods in `BookingConcurrencyIT`.

## Finding 2: The 26h estimate does not cover 18 test classes and about 110 test cases, and there is no cut list
- **Severity:** High
- **Location:** plan.md "Phases" table; Phase 04 "Overview" (4h); Phase 05 "Overview" (6h); Phase 07 "Overview" (4h)
- **Flaw:** The plan specifies 18 test classes (2 in phase 01/02 setup, 2 in phase 03, 4 in phase 04, 4 in phase 05, 1 large one in phase 06, 4 in phase 07) and roughly 110 individual test cases. Several are hard concurrency or async tests (lock-holder plus `pg_stat_activity` polling, Awaitility audit polling, partition DDL). On top of that the machine has no JDK 21, Maven or Docker yet ("Dependencies / risks"). Phase 07's 4h has to cover a bounded executor, a request-wrapper filter with a payload sanitizer, a `SmartLifecycle` partition job plus a cron job, three metrics, actuator wiring, 4 ITs and a compose smoke run with prod logs. Phase 04's 4h has to cover the sweep logic, the availability endpoint, 4 catalog endpoints with 4 DTO families, and 4 test classes.
- **Failure scenario:** Phases 04, 05 and 07 overrun. Because "Order is strict" and every phase must end with `./mvnw verify` green, the overrun lands on phase 07 (P2). That phase holds the user-fixed audit decision and §12 #9 ("verified to record in request_audit"). Those end up rushed or half-done, and nothing in the plan says what to drop first.
- **Evidence:** `effort: 26h`; phase 07 "Effort: 4h" with commits for three separate concerns; there is no "MVP cut" or "if over budget" section in plan.md.
- **Suggested fix:** Apply Findings 1, 4, 7 and 9 to remove about 30–40 test cases and 4–5 classes. Then re-estimate phase 07 at 6h or more and phase 04 at 5h. Add a short ordered cut list to plan.md for nice-to-have items: `ActuatorIT`, the `scheduler.availability.query.duration` timer test, the Swagger manual check, and the second compose smoke run.

## Finding 3: The one test the plan calls the release gate runs only after 13h of unrelated plumbing
- **Severity:** High
- **Location:** plan.md "Phases" ("Order is strict") and "Dependencies / risks"; Phase 05 "Risk Assessment"
- **Flaw:** The plan says the exclusion-constraint wait and retry behaviour is "proven only by `BookingConcurrencyIT`; it is the release gate". Its own risk section admits that behaviour might not work as assumed ("Exclusion check may not wait like unique checks… investigate before moving on"). Even so, the test is scheduled after the error catalog (phase 03) and the full availability and catalog surface (phase 04), which is 13h in.
- **Failure scenario:** At hour 13 the burst returns spurious `KUSS_NO_CAPACITY`, or the retry loop interacts badly with `TransactionTemplate` or `JdbcClient` connection binding. Fixing it changes the candidate SQL, the shared `TimeSlotUtils.localDay` usage, or the DTO shapes. Phase 04 code and tests then need rework, and less than half the budget is left.
- **Evidence:** Phase table order 1→2→3→4→5; plan.md: "Exclusion-constraint wait + retry semantics proven only by `BookingConcurrencyIT`; it is the release gate."; Phase 05 "Risk Assessment" first bullet.
- **Suggested fix:** Move phase 05 (booking, retry, burst IT) directly after phase 02. It only needs the schema, `SqlErrorUtils` and a stub 404/400 via `SchedulerException`. Do error-handling polish and catalog reads after the core risk is retired. If moving the phase is too disruptive, run a 1h spike of the raw-JDBC burst against the V1 schema at the end of phase 02.

## Finding 4: TimeSlotUtils has two validators that one existing method already covers
- **Severity:** Medium
- **Location:** Phase 04, "Architecture" (`TimeSlotUtils`) and "Implementation Steps" 1; Phase 05 step 5 (`loadAndValidate` using `TimeSlotUtils`)
- **Flaw:** The plan builds `candidateStarts`, `isOnGrid`, `isWithinHours`, `localDay`, `openHours` and a nested `Window` record, and then unit-tests grid and hours checks separately (5 bullets). Booking validation for "on grid, inside hours, not in the past, DST-correct" is the same question as "is this instant one of the candidate starts for its local date". All three failures map to the same `KUSS_VALIDATION_FAILED` anyway.
- **Failure scenario:** `isWithinHours` checks against the start's local date, while `candidateStarts` steps from `date.atTime(open).atZone(zone)`. On a DST day or a near-midnight close, one edge rule can differ. When that happens the availability endpoint offers a slot that booking rejects with 400, or the reverse. Keeping two code paths means maintaining two edge-case test sets that can drift apart.
- **Evidence:** `boolean isOnGrid(Instant start, ZoneId zone)`, `boolean isWithinHours(Instant start, int durationMinutes, ZoneId zone, LocalTime open, LocalTime close)`, and the grid and hours test bullets in phase 04 step 1.
- **Suggested fix:** In booking, call `candidateStarts(localDate(start, zone), zone, open, close, duration, now).contains(start)` and return a single 400 with the detail "desiredStartTime must be a future 30-minute slot within opening hours". Delete `isOnGrid`, `isWithinHours` and their tests. Keep one booking IT per rejection kind (off-grid, past, after close).

## Finding 5: `response_status` and `AppointmentResultDto` store a value that never varies
- **Severity:** Medium
- **Location:** Phase 02, "Architecture" (`idempotency_key.response_status`); Phase 05, "Requirements" (`AppointmentResultDto(httpStatus, AppointmentDto)`); Phase 06, "Key Insights" bullet 1
- **Flaw:** Only successful outcomes are stored ("failure not stored", phase 05 step 4). Book always succeeds with 201 and cancel always with 200 (including the already-cancelled path). The claim row is invisible to other transactions until commit, and by commit it is already completed, so the "NULL while uncommitted" state is never read either. Persisting the status, wrapping every result in a status-carrying DTO, and having the controller do `ResponseEntity.status(result.httpStatus())` add a column, a record and mapping code for a constant.
- **Failure scenario:** This is pure carrying cost: one extra DTO threaded through `book`, `bookOnce`, `cancel`, the idempotency service, the replay deserialisation and the controllers, plus tests asserting "stored status on replay". It also invites a future bug where someone stores a 409 and replays an error, which contradicts the "failures not stored" rule.
- **Evidence:** "`response_status int, -- NULL only while claim uncommitted`"; "`book(...)` → `AppointmentResultDto(httpStatus, AppointmentDto)`; 201 fresh, stored status on replay".
- **Suggested fix:** Drop `response_status` and `AppointmentResultDto`. Services return `AppointmentDto`, the controllers use `@ResponseStatus(CREATED)` and `@ResponseStatus(OK)`, and `response_body` alone holds the replay payload.

## Finding 6: The `service_type` metric tag adds a database round trip to every booking
- **Severity:** Medium
- **Location:** Phase 07, "Key Insights" last bullet and "Architecture" (`book()` metric wrapper); plan.md "Planner open questions" bullet 3; Phase 07 `MetricsIT` (`ZZZ` → `unknown` case)
- **Flaw:** The counter wrapper sits outside the retry loop, so it cannot see the service type `loadAndValidate` already loaded. The plan solves this with an extra `serviceTypeRepository.existsById(code)` on every booking, including replays and 409s. The outcome already says whether the code was valid: `confirmed`, `KUSS_NO_CAPACITY` and `KUSS_VEHICLE_ALREADY_BOOKED` can only happen after validation, and replays only happen for stored successes.
- **Failure scenario:** One extra query runs on the hottest write path, including during the burst, plus a dedicated `MetricsIT` case to prove the `unknown` branch. "Cost negligible" is asserted, not measured. At 100 write RPS this is 100 extra PK lookups per second that exist only for a tag.
- **Evidence:** "`service_type` tag must stay bounded: request code only if `serviceTypeRepository.existsById(code)`, else `unknown`."
- **Suggested fix:** Use `tag = outcome ∈ {confirmed, KUSS_NO_CAPACITY, KUSS_VEHICLE_ALREADY_BOOKED} ? request.serviceTypeCode() : "unknown"`. This needs no query and stays bounded, because those outcomes imply the code passed validation.

## Finding 7: Several tests check Postgres, Spring Boot and springdoc rather than this code
- **Severity:** Medium
- **Location:** Phase 01 step 5 (`SchedulerApplicationIT`); Phase 02 steps 1–2 (`SchemaConstraintsIT`, `SeedDataIT`); Phase 06 step 1 (OpenAPI case); Phase 07 step 3 (`ActuatorIT`, Prometheus name grep in `MetricsIT`)
- **Flaw:**
  - `SchemaConstraintsIT` requires at least 9 tests. Beyond the 3 EXCLUDEs, back-to-back and cancelled-frees, it tests that `NOT NULL` raises 23502, that `CHECK status IN (...)` rejects `PENDING`, that duration 45 fails its CHECK, and that a row lands in a DEFAULT partition. Those are Postgres guarantees.
  - `SeedDataIT` re-asserts seed counts (K=3 and K=2) that the burst ITs already fail loudly on.
  - `SchedulerApplicationIT.contextLoads` is redundant once any other IT exists.
  - `ActuatorIT` checks that liveness and readiness return UP and that a `Tracer` bean exists.
  - The `/v3/api-docs contains /api/v1/appointments` case and the `/actuator/prometheus contains …_total` grep check framework wiring.
- **Failure scenario:** About 15 extra test methods across 4–5 classes to write and keep green, inside an estimate that is already too small (Finding 2). None of them would catch a regression in booking correctness.
- **Evidence:** "`SchemaConstraintsIT` (≥9 tests)"; "NULL bay → SQLState 23502; … status `PENDING` → `chk_appointment_status`; service_type duration 45 → check violation"; "`ActuatorIT`: `/actuator/health/liveness` + `/readiness` → `UP`; … `io.micrometer.tracing.Tracer` bean present".
- **Suggested fix:**
  - Trim `SchemaConstraintsIT` to the 5 behaviour cases: 3 overlaps, back-to-back, and cancelled-frees.
  - Delete `SeedDataIT`. Prove the `skillCodes` mapping through `CatalogApiIT`, which already reads technicians with skills.
  - Delete `SchedulerApplicationIT` once phase 02 has an IT.
  - Delete `ActuatorIT`. Keep the probe and Prometheus check as a manual `curl` in Success Criteria, as phase 07 already does.

## Finding 8: The audit-failure test renames a shared table while other tests write audit rows in the background
- **Severity:** Medium
- **Location:** Phase 07, "Implementation Steps" 1, last bullet
- **Flaw:** All ITs share one cached context and one container (phase 01 step 6), and every `/api/**` request from every test class sends an audit write to the `auditExecutor`. `ALTER TABLE request_audit RENAME TO request_audit_off` takes ACCESS EXCLUSIVE on the partitioned parent while queued writes from earlier tests (and this class's own earlier tests) are still draining.
- **Failure scenario:** Writes queued from the previous test hit the renamed table and fail. That bumps `scheduler.audit.writes{status=failure}`, so the "+1" assertion is off by k and the test flakes. Concurrently, any Awaitility lookup still waiting for its row times out. If the `finally` rename fails, for example on a lock timeout, every later IT in the run loses auditing.
- **Evidence:** "audit failure: `ALTER TABLE request_audit RENAME TO request_audit_off`, request → still 2xx, `scheduler.audit.writes{status=failure}` +1; rename back in `finally`".
- **Suggested fix:** Call `requestAuditService.write(...)` directly with a DTO that violates the schema, such as `request_payload = "not json"` (the `CAST AS jsonb` fails) or a path longer than 255 characters. Then assert the failure counter changed by exactly 1. This needs no DDL and works on a real DB with no mocks. "Response still 2xx when audit fails" is already guaranteed by design because dispatch happens after the response in the filter's `finally`.

## Finding 9: The DEFAULT audit partition creates three extra mechanisms to manage it
- **Severity:** Medium
- **Location:** Phase 02, "Architecture" (`request_audit_default`) and step 1 (default-landing test); Phase 07, "Key Insights" bullet 5, "Requirements" (maintenance), "Risk Assessment" bullet 1, `PartitionMaintenanceIT`
- **Flaw:** The fixed decision is daily partitions with 14-day retention, and it says nothing about a DEFAULT partition. Adding one causes several knock-on costs:
  - Creating a day that already has rows in DEFAULT fails, which forced the `SmartLifecycle` `Integer.MIN_VALUE` startup-ordering argument (A-023 #1).
  - It adds a separate `DELETE FROM request_audit_default` retention step.
  - It adds a "self-heals next day" failure mode.
  - It adds a schema test and two `PartitionMaintenanceIT` cases.

  The job already keeps 7 days of partitions ahead (D..D+7). DEFAULT therefore only matters if maintenance fails 8 days in a row.
- **Failure scenario:** The ordering guarantee depends on Spring Boot internals: the `WebServerStartStopLifecycle` phase value, and the assumption that no audited request can arrive before lifecycle start. If that is wrong, today's partition creation fails, the plan's "log, next day self-heals" applies, and every row for that day sits in DEFAULT, outside the 14-day DROP path. Defending this costs reasoning, tests and a cross-review round.
- **Evidence:** "`DROP` never prunes `request_audit_default` → separate `DELETE`. Creating a day already present in DEFAULT fails → log, next day self-heals."; "parent insert at D−30 (lands in default) → deleted".
- **Suggested fix:** Drop the DEFAULT partition. Create D..D+7 at startup (any hook works, because the worst case is that the first audited request fails and is counted as `status=failure`) and daily. Remove the default `DELETE`, the default-landing tests and the startup-ordering requirement. An audit insert with no partition becomes an ordinary counted audit failure, which the plan already accepts for executor saturation.
