# Red Team — Assumption Destroyer (2026-10-30)

Plan: `plans/261030-KUSS-02-unified-service-scheduler` · Scope: plan.md + phase-01..07, research-01/02, draft §6–§12, PDF Scenario A.

## Finding 1: `@ElementCollection` mapping as written fails `ddl-auto: validate`
- **Severity:** High
- **Location:** Phase 02, section "Architecture" (Entities bullet)
- **Flaw:** `TechnicianEntity` is specified as `@ElementCollection @CollectionTable(name = "technician_skill") @Column(name = "service_type_code") Set<String> skillCodes` with no `joinColumns`. JPA's default join column is `<entityName>_<pkColumn>` = `TechnicianEntity_id`, which Boot's `CamelCaseToUnderscoresNamingStrategy` turns into `technician_entity_id`. The V1 table has `technician_id`.
- **Failure scenario:** Phase 02 step 3/4: context startup → `SchemaManagementException: missing column [technician_entity_id] in table [technician_skill]` → every `*IT` (all share one context) fails; phase-02 exit gate red. The `*Entity` suffix convention makes this default wrong by construction.
- **Evidence:** "`TechnicianEntity` (+ `@ElementCollection @CollectionTable(name = "technician_skill") @Column(name = "service_type_code") Set<String> skillCodes`, lazy)"; Risk section only anticipates `int`/`time` mismatches.
- **Suggested fix:** `@CollectionTable(name = "technician_skill", joinColumns = @JoinColumn(name = "technician_id"))`. Add one line to Key Insights: every implicit name derived from an `*Entity` class name is wrong; set it explicitly.

## Finding 2: Concurrent EXCLUDE inserts deadlock by design; the plan models it as "loser waits for winner"
- **Severity:** High
- **Location:** Phase 05, "Key Insights" bullet 2 and "Risk Assessment"; research-02 §7
- **Flaw:** PostgreSQL inserts the index tuple first and then scans for conflicts (execIndexing.c header: "There is a chance of deadlock, if two backends insert a tuple at the same time... The deadlock detector will ... abort one"). Ordinary exclusion checks use `CEOUC_WAIT`, so two simultaneous conflicting inserters wait on each other. The deadlock detector only runs after `deadlock_timeout` (default **1 s**). In an N=20 single-slot burst this is the common path, not an edge case. research-02 §7 states the opposite ("Booking-vs-booking cycles should not occur"). The retry tag `deadlock` exists, but the plan never accounts for the cost.
- **Failure scenario:** (a) `BookingConcurrencyIT` and `AppointmentApiIT` #11 take many seconds per run because each deadlock round costs 1 s. Any future `get(timeout)` or Awaitility bound added "for safety" flakes, so the 5× stability run is unreliable. (b) Each waiting attempt holds a Hikari connection for that second. The prod profile keeps the default pool of 10 (only `application-test.yml` raises it), and virtual threads accept unbounded requests. A reviewer's 20–30-request burst against `docker compose` therefore queues on Hikari, and the plan's idempotency waits (no `lock_timeout`, by decision) add more connection holders. (c) With 1 s per deadlock round, `MAX_ATTEMPTS = 100` permits ~100 s requests. (d) Phase 05 Risk tells the implementer to treat "spurious" behaviour as a bug and "investigate before moving on". An implementer who sees `40P01` will chase a non-bug.
- **Evidence:** "Losing inserter waits for the winner; winner commits → 23P01"; research-02 §7 "cycles should not occur ... not proven"; `application.yml` has no `hikari.maximum-pool-size`.
- **Suggested fix:** Correct the Key Insight: simultaneous inserters may get `40P01`, and that case is expected and retried. Start Postgres with `-c deadlock_timeout=100ms` in both `TestcontainersConfiguration` (`withCommand`) and compose `db.command`. Set an explicit prod pool size with a `ponytail:` note. Optionally assert `gist_retries{conflict_type=deadlock}` ≥ 0 so it is visible in the report. Fix the research-02 §7 claim.

## Finding 3: A vehicle double-booking returns `KUSS_NO_CAPACITY` instead of `KUSS_VEHICLE_ALREADY_BOOKED` when the slot is full
- **Severity:** Medium
- **Location:** Phase 05, "Architecture" (`bookOnce` → `pickCandidate` before `insert`)
- **Flaw:** The vehicle overlap is detected only by the INSERT's constraint violation. `pickCandidate` throws `KUSS_NO_CAPACITY` when either candidate list is empty, which happens before any INSERT. The candidate queries ignore the vehicle.
- **Failure scenario:** Vehicle V holds 10:00 OIL_CHANGE and the other two bays get filled. A client re-submits V for 10:00 with a new key and gets 409 `KUSS_NO_CAPACITY`. §12 #4 says "repeated booking on the same vehicle returns 409 `KUSS_VEHICLE_ALREADY_BOOKED`". The same happens for V requesting an overlapping BRAKE_REPAIR when both BRAKE techs are busy. The metric `result` tag and the audit `error_code` both record the wrong cause. The tests only cover the non-full case, so this is never caught.
- **Evidence:** `CandidateDto candidate = pickCandidate(context); // 409 NO_CAPACITY when a list is empty` precedes `appointmentRepository.insert(...)`; VehicleOverlapIT only books V into an empty slot.
- **Suggested fix:** In `loadAndValidate`, add one indexed `EXISTS` for a CONFIRMED overlap on `vehicle_id` (same `[)` predicate) that throws `KUSS_VEHICLE_ALREADY_BOOKED`. Keep the constraint as the race backstop. Add a VehicleOverlapIT case: fill the slot, then re-book V → VEHICLE_ALREADY_BOOKED.

## Finding 4: The VehicleOverlapIT cross-dealership case cannot produce a 409 with the specified bookings
- **Severity:** Medium
- **Location:** Phase 05, "Implementation Steps" step 3
- **Flaw:** The first booking is "vehicle V OIL_CHANGE 10:00" London, which is 10:00Z on the GMT target date. New York is open 08:00–17:00 EST = 13:00–22:00Z. No valid NY start overlaps 10:00–10:30Z. The required NY start (05:00 local) fails the hours check.
- **Failure scenario:** The test as described expects 409 for "V at New York overlapping in absolute time" and gets 400 `KUSS_VALIDATION_FAILED` from `isWithinHours`. The implementer then either drops the case or weakens it, and §11 #6 (vehicle overlap across dealerships) goes untested. Hardcoding a pair instead flakes seasonally: between US DST start (2nd Sunday of March) and UK DST start (last Sunday of March) the offsets differ by 4 h, not 5.
- **Evidence:** "vehicle V OIL_CHANGE 10:00 → 201; ... V at New York overlapping in absolute time → 409 too"; seed NY 08:00–17:00 `America/New_York`.
- **Suggested fix:** Book V in London at 14:00 local. Derive the NY start as that same `Instant` converted to `America/New_York`, and assert it is on the grid and within NY hours before the call. That lands on 09:00 or 10:00 NY local in every season.

## Finding 5: The sanitized-detail guarantee is only enforced for 500s; 400 details leak Java type names
- **Severity:** Medium
- **Location:** Phase 03, "Requirements" mapping table and "Implementation Steps" step 1
- **Flaw:** The plan does not fix the `detail` text for `HttpMessageNotReadableException` or `TypeMismatchException`. Their messages contain JVM internals, e.g. "JSON parse error: Cannot deserialize value of type `java.time.OffsetDateTime` from String ..." or "Failed to convert value of type 'java.lang.String' to required type 'java.util.UUID'". The only sanitization test covers the 500 path. In addition, `jakarta.validation.ConstraintViolationException` is unmapped. If anyone adds the common `@Validated` on a controller, the 129-char key case goes through the catch-all and becomes 500 `KUSS_INTERNAL_ERROR`. The catch-all for `ErrorResponse` rebuilds the body via `problem(...)`, which drops `ErrorResponse.getHeaders()`. A 405 then loses its RFC 9110-mandated `Allow` header.
- **Failure scenario:** AppointmentApiIT #1 "start without offset" returns 400 with a `detail` naming `java.time.OffsetDateTime` and possibly the `BookAppointmentDto` FQN. That breaks draft §8.1 "internal Java class names are never leaked". No test notices.
- **Evidence:** Phase 03 Security: "only `SchedulerException` messages ... and Spring's generic `ErrorResponse` details reach clients", but `HttpMessageNotReadableException` is not an `ErrorResponse` and no detail text is specified. Test step 1 asserts sanitization only for the `IllegalStateException` → 500 case.
- **Suggested fix:** Use fixed details: "Malformed request body." and "Parameter '<name>' has an invalid value." (the name from `MethodArgumentTypeMismatchException#getName`). Add `SchedulerGlobalHandlerTest` assertions that no 4xx `detail` contains `java.` or `com.keyloop`. Either map `ConstraintViolationException` → 400 or state "no `@Validated` on controllers". Copy `errorResponse.getHeaders()` onto the response for the `ErrorResponse` branch.

## Finding 6: PDF core requirement 3 (customer association) is never asserted
- **Severity:** Medium
- **Location:** plan.md "Test map"; Phase 05/06 test steps
- **Flaw:** PDF Scenario A #3 requires the appointment record to associate "the customer, vehicle, technician, and service bay". The customer is derived from `vehicle.customer_id` (fixed decision). No test checks that `customerId` in the response or the DB row equals the vehicle's owner, or that `endTime = startTime + duration`. BookingConcurrencyIT checks bay and tech scoping only. AppointmentApiIT "reads" only checks that "GET by id returns booked row".
- **Failure scenario:** An implementer maps `customerId` from the wrong source in `AppointmentDto.confirmed(context, ...)`, e.g. the dealership or a null-safe default. The FK still passes because any seeded customer is valid, and the whole suite stays green. The acceptance criterion the PDF evaluates first is then unverified. Seed owners are round-robin over 3 customers, so a mix-up is plausible.
- **Evidence:** Test map has no row for "record associates customer/vehicle/tech/bay"; the requirement appears only in the PDF.
- **Suggested fix:** In AppointmentApiIT's happy path, assert `customerId == SeedIds.customer((n-1)%3+1)` for vehicle n. Also assert `endTime - startTime == duration`, `technicianId` ∈ qualified set and `serviceBayId` ∈ dealership active bays, both on the response and on the DB row. Add the row to the Test map.

## Finding 7: The Phase-02 default-partition test breaks once Phase 07 creates partitions at startup
- **Severity:** Medium
- **Location:** Phase 02, "Implementation Steps" step 1 (last bullet) vs Phase 07 "Requirements" (startup `SmartLifecycle` creating D..D+7)
- **Flaw:** "insert one `request_audit` row with no daily partition → lands in `request_audit_default`" gives no date. Written in Phase 02 it will naturally use `now()`. From Phase 07 on, the shared context creates partitions D..D+7 before any test runs, so the row routes to `request_audit_pYYYYMMDD`. `IntegrationTestSupport` never truncates `request_audit`, so leftover default rows also persist across classes.
- **Failure scenario:** The Phase 07 exit gate (`./mvnw verify`, all earlier tests) fails in `SchemaConstraintsIT` with the row found in the daily partition, not in the default partition. If instead someone picks a near-future date inside D+8..D+14, the row sits in DEFAULT. When the daily job later reaches that day, `CREATE TABLE ... PARTITION OF` fails with "updated partition constraint for default partition would be violated", which is only logged.
- **Evidence:** Phase 02 step 1 bullet; Phase 07 "create D..D+7 UTC partitions" at `Integer.MIN_VALUE` startup; truncate list = `appointment, idempotency_key`.
- **Suggested fix:** Pin the Phase-02 test row to a fixed past instant (e.g. `2020-01-01T00:00Z`) and delete it in `finally`. Note in Phase 07 that ITs must never write audit rows dated D+8 or later.

## Finding 8: Booking SQL inline in methods cannot pass `MethodLength` max 20 with `countEmpty=true`
- **Severity:** Medium
- **Location:** Phase 05, "Architecture" (candidate queries) and Phase 07 "Architecture" (maintenance SQL); Phase 01 step 3
- **Flaw:** The plan shows the free-technician query (11 SQL lines) and says it lives in `AppointmentWriteRepositoryImpl`, but never says where the SQL text goes. A method containing it needs: text block (13 lines incl. delimiters), `jdbcClient.sql(...)`, six `.param(...)` (dealershipId, serviceTypeCode, start, end, dayStart, dayEnd), `.query(...)`, `.list()`, plus signature and braces. That totals ≈ 23–25 lines. Because `countEmpty=true`, blank and comment lines inside count too. The shown method snippets fit; the unshown ones do not.
- **Failure scenario:** `./mvnw validate` fails on `AppointmentWriteRepositoryImpl.findFreeTechnicians` (and likely `IdempotencyServiceImpl.claim`, which has a SELECT plus a mismatch branch plus an INSERT with catch). Under the gate, the implementer splits methods arbitrarily mid-phase.
- **Evidence:** Phase 01: "`MethodLength max=20 countEmpty=true`"; Phase 05 SQL blocks with no "SQL as `private static final String`" instruction.
- **Suggested fix:** Add one rule to Phase 05/07 Key Insights: all SQL lives in `private static final String` text-block constants, and `claim` splits into `findExisting` and `insertClaim`.

## Unresolved questions
- Is the 1 s-per-deadlock latency of Finding 2 acceptable for the demo/video burst, or should `deadlock_timeout` be lowered in compose as well as in tests?
- For a full slot plus an already-booked vehicle (Finding 3), which code wins? The draft does not rank `KUSS_VEHICLE_ALREADY_BOOKED` against `KUSS_NO_CAPACITY`.

## Sources
- PostgreSQL `execIndexing.c` (REL_17_STABLE) header on exclusion-constraint deadlocks: https://github.com/postgres/postgres/blob/REL_17_STABLE/src/backend/executor/execIndexing.c
- Spring Framework 7 `UNPROCESSABLE_CONTENT` (plan's 422 assumption holds): https://github.com/spring-projects/spring-framework/issues/35802
