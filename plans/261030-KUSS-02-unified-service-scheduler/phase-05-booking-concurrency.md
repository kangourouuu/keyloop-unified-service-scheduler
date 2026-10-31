# Phase 05 — Booking concurrency + idempotency

## Context Links
- Plan: [plan.md](./plan.md) · Draft §7.2, §11, §12 (#4, #5, #6, #8, #10, #11)
- [researcher-02](./research/researcher-02-postgres-concurrency.md) §1–§4, §7 · C-017 #1, #2, #6, #10

## Overview
- Priority: P1 (core correctness) · Status: Pending · Effort: 6h · Story: KUSS-04
- Commits: `[KUSS-04] Add idempotency key claim and replay`, `[KUSS-04] Add booking with exclusion-constraint retry`
- `AppointmentService.book` = whole-transaction retry loop over: claim key → load/validate → free candidates → pick → INSERT → complete key → commit.

## Key Insights
- A failed INSERT (23P01) aborts the PG tx → retry = **new tx per attempt** (`TransactionTemplate`). No savepoints / `NESTED` (Hibernate keeps failed state, researcher-02 §2, A-022 #1). `book()` must NOT be `@Transactional` (outer tx would pin one snapshot and kill the loop).
- Losing inserter waits for the winner; winner commits → 23P01; next attempt's fresh READ COMMITTED snapshot sees the winner → that bay/tech drops out. Empty candidates → `KUSS_NO_CAPACITY`. Loop ends by design.
- **Deadlocks are expected, not a bug** ("unprincipled deadlock", `execIndexing.c`): PG checks an EXCLUDE constraint *after* inserting its own index tuple, so two concurrent conflicting inserts each wait on the other's xid → after `deadlock_timeout` one aborts with 40P01. Unique btree indexes avoid this via speculative insertion; exclusion constraints do not (corrects researcher-02 §7 "no cycles"). The 40P01 retry handles it; `deadlock_timeout=100ms` (phase 01) keeps the loser's wait short. Tests never assert zero `deadlock` retries. <!-- Updated: Red Team #1 -->
- Bounded in time: `TransactionTemplate.setTimeout(5)` per attempt (JDBC statement timeout) + prod Hikari `connection-timeout: 3s`; no free connection → `CannotCreateTransactionException` → 500 `KUSS_INTERNAL_ERROR`, logged at WARN without stack trace. <!-- Updated: Red Team #4 -->
- Vehicle check runs before capacity: `EXISTS` vehicle-overlap precheck in `loadAndValidate` → `KUSS_VEHICLE_ALREADY_BOOKED` even when the slot is full (§12 #4); the vehicle EXCLUDE stays the race backstop. <!-- Updated: Red Team #8 -->
- Every instant written (`created_at`, times echoed in responses) = `clock.instant().truncatedTo(ChronoUnit.MICROS)` → Postgres µs round-trip equals the fresh response (replay bodies identical). <!-- Updated: Red Team #11 -->
- All SQL lives in `private static final String` text-block constants → every method stays ≤ 20 lines. <!-- Updated: Red Team #13 -->
- Writes + candidate queries via `JdbcClient` (joins the JPA tx connection); exception exposes `PSQLException` with structured constraint name.
- Claim-first idempotency: plain `INSERT` blocks on a concurrent uncommitted same-key claim; holder commits → 23505 (`DuplicateKeyException`) → 409 IN_FLIGHT; holder rolls back → our insert succeeds. **No `SET LOCAL lock_timeout`** (would also cap EXCLUDE waits and mislabel them).
- Hash over canonical fields, not raw body → whitespace/field-order/offset-equivalent requests replay instead of 422.
- Bind `Instant` as `OffsetDateTime` (`atOffset(UTC)`) — pgjdbc `Instant` binding not guaranteed.
- `SqlErrorUtils` needs `org.postgresql.util.PSQLException` at compile time → `postgresql` is compile scope (set in phase 01).

## Requirements
- `book(BookAppointmentDto, idempotencyKey)` → `AppointmentResultDto(httpStatus, AppointmentDto)`; 201 fresh, stored status on replay.
- Errors: unknown dealership/vehicle/inactive service type → 404; not in `candidateStarts(...)` (off-grid / past / outside hours) or date > today + 90 days → one 400 `KUSS_VALIDATION_FAILED`; no free pair → 409 `KUSS_NO_CAPACITY`; vehicle overlap (precheck or constraint) → 409 `KUSS_VEHICLE_ALREADY_BOOKED` (no retry, checked before capacity); same key other payload → 422; concurrent same key → 409 `KUSS_IDEMPOTENCY_IN_FLIGHT`; retry cap or no DB connection → 500 `KUSS_INTERNAL_ERROR`.
- Bay = random among free active bays at the dealership; tech = qualified, active, free, fewest CONFIRMED minutes on that local day (ties random).
- Metric (needed by `VehicleOverlapIT`): counter `scheduler.booking.gist_retries{conflict_type=bay|technician|deadlock}` (Prometheus: `…_total`), all three tag values pre-registered in the constructor; tests read the sum over `meterRegistry.find(...).counters()`. <!-- Updated: Red Team #11 -->

## Architecture
```java
private static final int MAX_ATTEMPTS = 100;   // ≥ N concurrent + K: each attempt loses to one committed winner or one deadlock

public AppointmentResultDto book(BookAppointmentDto request, String idempotencyKey) {   // no @Transactional
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
        try {
            return transactionTemplate.execute(status -> bookOnce(request, idempotencyKey));
        } catch (DataAccessException ex) {
            handleConflict(ex);   // vehicle → throw 409; non-retryable → rethrow; bay/tech/40P01 → count + loop
        } catch (CannotCreateTransactionException ex) {   // pool exhausted after connection-timeout
            log.warn("Booking aborted, no DB connection: {}", ex.getMessage());
            throw new SchedulerException(ErrorCode.KUSS_INTERNAL_ERROR, ErrorMessageConstants.BOOKING_NOT_COMPLETED);
        }
    }
    log.error("Booking retry cap {} reached, dealershipId={}", MAX_ATTEMPTS, request.dealershipId());
    throw new SchedulerException(ErrorCode.KUSS_INTERNAL_ERROR, ErrorMessageConstants.BOOKING_NOT_COMPLETED);
}

private AppointmentResultDto bookOnce(BookAppointmentDto request, String idempotencyKey) {
    Optional<AppointmentResultDto> replay = idempotencyService.claim(idempotencyKey, request.canonicalRequest());
    if (replay.isPresent()) {
        return replay.get();
    }
    BookingContext context = loadAndValidate(request);          // 404 / 400 / 409 VEHICLE (EXISTS precheck)
    CandidateDto candidate = pickCandidate(context);             // 409 NO_CAPACITY when a list is empty
    AppointmentDto appointment = appointmentMapper.confirmed(context, candidate, clock.instant().truncatedTo(ChronoUnit.MICROS));
    appointmentRepository.insert(appointment);                   // JdbcClient; may throw 23P01
    AppointmentResultDto result = AppointmentResultDto.builder().status(201).appointment(appointment).build();
    idempotencyService.complete(idempotencyKey, result);
    return result;
}
```
`handleConflict`: `constraint = SqlErrorUtils.constraintName(ex)`, `state = SqlErrorUtils.sqlState(ex)`; `excl_appointment_vehicle_overlap` → `SchedulerException(KUSS_VEHICLE_ALREADY_BOOKED)`; state not in {`23P01`, `40P01`} → rethrow `ex` (→ 500); else increment retry counter (`bay` / `technician` / `deadlock`). `SqlErrorUtils` walks the cause chain for `PSQLException`, reads `getSQLState()` and `getServerErrorMessage().getConstraint()` (`""` when absent).

Idempotency (`IdempotencyServiceImpl`, every public method `@Transactional(propagation = Propagation.MANDATORY)`; `claim` = `findExisting(key)` → replay/422, else `insertClaim(key, hash)`, each a private method over one SQL constant):
```sql
SELECT request_hash, response_status, CAST(response_body AS text) AS body FROM idempotency_key WHERE idempotency_key = :key
-- found: same hash → replay (status, JsonMapper.readValue(body, AppointmentDto.class)); else 422 MISMATCH
INSERT INTO idempotency_key (idempotency_key, request_hash, created_at) VALUES (:key, :hash, :now)
-- DuplicateKeyException → SchedulerException(KUSS_IDEMPOTENCY_IN_FLIGHT)
UPDATE idempotency_key SET response_status = :status, response_body = CAST(:body AS jsonb) WHERE idempotency_key = :key
```
Hash = `HexFormat.of().formatHex(SHA-256(canonicalRequest))`. `BookAppointmentDto.canonicalRequest()` = `"POST /api/v1/appointments|" + dealershipId + "|" + vehicleId + "|" + serviceTypeCode + "|" + desiredStartTime.toInstant()`.

Candidate queries (`AppointmentWriteRepositoryImpl`, same `[)` predicate as the constraints, literal `'CONFIRMED'`):
```sql
-- free bays
SELECT b.id FROM service_bay b
WHERE b.dealership_id = :dealershipId AND b.active
  AND NOT EXISTS (SELECT 1 FROM appointment a WHERE a.service_bay_id = b.id AND a.status = 'CONFIRMED'
                  AND tstzrange(a.start_time, a.end_time, '[)') && tstzrange(:start, :end, '[)'))
-- free qualified techs + booked minutes that local day
SELECT t.id, CAST(COALESCE(SUM(EXTRACT(EPOCH FROM (d.end_time - d.start_time)) / 60), 0) AS bigint) AS booked_minutes
FROM technician t
JOIN technician_skill s ON s.technician_id = t.id AND s.service_type_code = :serviceTypeCode
LEFT JOIN appointment d ON d.technician_id = t.id AND d.status = 'CONFIRMED'
     AND d.start_time >= :dayStart AND d.start_time < :dayEnd
WHERE t.dealership_id = :dealershipId AND t.active
  AND NOT EXISTS (SELECT 1 FROM appointment a WHERE a.technician_id = t.id AND a.status = 'CONFIRMED'
                  AND tstzrange(a.start_time, a.end_time, '[)') && tstzrange(:start, :end, '[)'))
GROUP BY t.id
-- vehicle precheck (loadAndValidate)
SELECT EXISTS (SELECT 1 FROM appointment a WHERE a.vehicle_id = :vehicleId AND a.status = 'CONFIRMED'
               AND tstzrange(a.start_time, a.end_time, '[)') && tstzrange(:start, :end, '[)'))
```
`AppointmentRepository extends JpaRepository<AppointmentEntity, UUID>, AppointmentWriteRepository` (Spring Data fragment: `findFreeBayIds`, `findFreeTechnicians`, `existsVehicleOverlap`, `insert(AppointmentDto)`, `markCancelled(UUID, Instant)` — last one used in phase 06).
`CandidateSelectionUtils.pick(List<UUID> freeBayIds, List<TechnicianLoadDto> freeTechnicians, RandomGenerator random)` → `CandidateDto`; service passes `ThreadLocalRandom.current()`.

## Related Code Files
`{root}` = `/Users/kangourouuu/orca/workspaces/keyloop-unified-service-scheduler/tilefish`. Create:
- `{root}/src/main/java/com/keyloop/scheduler/appointment/{AppointmentService,AppointmentServiceImpl}.java`
- `{root}/src/main/java/com/keyloop/scheduler/appointment/{AppointmentWriteRepository,AppointmentWriteRepositoryImpl}.java`
- `{root}/src/main/java/com/keyloop/scheduler/appointment/{IdempotencyService,IdempotencyServiceImpl}.java` (same package: only appointments use it, avoids a package cycle)
- `{root}/src/main/java/com/keyloop/scheduler/appointment/CandidateSelectionUtils.java`
- `{root}/src/main/java/com/keyloop/scheduler/appointment/{BookAppointmentDto,AppointmentDto,AppointmentResultDto,TechnicianLoadDto,CandidateDto}.java`
- `{root}/src/main/java/com/keyloop/scheduler/common/util/SqlErrorUtils.java`
- `{root}/src/test/java/com/keyloop/scheduler/appointment/{CandidateSelectionTest,BookingConcurrencyIT,VehicleOverlapIT,IdempotencyIT}.java`
Modify: `{root}/src/main/java/com/keyloop/scheduler/appointment/AppointmentRepository.java` (extend fragment).

## Implementation Steps
1. Tests first (red) — `CandidateSelectionTest` (unit, seeded `new Random(42)`): picks the min-minutes tech; with two tied min techs both chosen across 100 picks, never the heavier one; bay always from the free list and all 3 bays seen across 100 picks.
2. `BookingConcurrencyIT` (service-level; `application-test.yml` Hikari pool 30):
   - helper `burst(code, n, k)`: `newFixedThreadPool(n)` + `CountDownLatch` start gate; N=20 calls with DISTINCT vehicles (`SeedIds.vehicle(1..20)`) and DISTINCT keys, same London start (date+7 10:00 local); collect `"201"` or `SchedulerException.code()`.
   - `bayBound`: OIL_CHANGE → exactly 3×201, 17×`KUSS_NO_CAPACITY`. `techBound`: BRAKE_REPAIR → exactly 2×201, 18×NO_CAPACITY.
   - DB after each: `count(*) = count(DISTINCT service_bay_id) = count(DISTINCT technician_id) = K` for CONFIRMED rows; bays ⊆ London active bays; BRAKE techs ⊆ {101, 103} (unqualified/other dealership never assigned). `deadlock` retries may be > 0 — never asserted to be 0.
   - `cancelledRowFreesCapacity`: fill 3 OIL_CHANGE → 4th NO_CAPACITY → JDBC `UPDATE … SET status='CANCELLED', cancelled_at=now()` one row → 4th (new key) → 201 on the freed bay.
   - `bookingLinksCustomerVehicleTechnicianBay` (PDF Req 3): one BRAKE_REPAIR booking → response AND DB row: `customerId` = the vehicle's owner, `endTime = start + 90 min`, technician has skill BRAKE_REPAIR and is active at London, bay is active at London. <!-- Updated: Red Team #12 -->
3. `VehicleOverlapIT`: vehicle V OIL_CHANGE 15:00 London → 201; V BRAKE_REPAIR 14:30 (new key) → `KUSS_VEHICLE_ALREADY_BOOKED` and `scheduler.booking.gist_retries` sum unchanged; V at New York at the **same instant** as the London booking (derived from it: 10:00 or 11:00 NY local, on grid and inside 08:00–17:00) → 409 too; two threads same vehicle, different keys, same slot → one 201 + one VEHICLE_ALREADY_BOOKED; **full slot**: vehicles 2–4 fill OIL_CHANGE 10:00, vehicle 2 then asks BRAKE_REPAIR 10:00 → `KUSS_VEHICLE_ALREADY_BOOKED`, not `KUSS_NO_CAPACITY` (precheck). <!-- Updated: Red Team #8, #11 -->
4. `IdempotencyIT`:
   - replay: same key + same request → same appointment id, status 201, 1 DB row; `+01:00` vs `Z` equivalent start → replay (not 422).
   - mismatch: same key, other vehicle → 422 `KUSS_IDEMPOTENCY_KEY_MISMATCH`, still 1 row.
   - failure not stored: key on a full slot → NO_CAPACITY; free a bay via JDBC; same key → 201.
   - in-flight (deterministic; harness local to `IdempotencyIT`, the only user <!-- Updated: Red Team #3, #5 -->): holder `Connection` from `DataSource` in try-with-resources, `autoCommit=false`, `holderPid = SELECT pg_backend_pid()`, `INSERT INTO idempotency_key (idempotency_key, request_hash) VALUES ('k-held', 'x')`; submit `book(…, "k-held")` on another thread; Awaitility (≤ 5s) until `SELECT count(*) FROM pg_stat_activity WHERE pg_blocking_pids(pid) @> ARRAY[:holderPid]` = 1; holder `commit()` → `future.get(10, SECONDS)` fails `KUSS_IDEMPOTENCY_IN_FLIGHT`. Variant: holder `rollback()` → future returns 201.
5. Implement: DTO records (`BookAppointmentDto` with `@NotNull`/`@NotBlank @Size(max = 40)`/`OffsetDateTime desiredStartTime`; `@Builder` on every record; MapStruct `AppointmentMapper` owns `toDto(AppointmentEntity)` and `confirmed(context, candidate, createdAt)`, with no mapping inside DTOs), `SqlErrorUtils`, `CandidateSelectionUtils`, write-repository fragment, `IdempotencyServiceImpl`, `AppointmentServiceImpl.book` (+ `loadAndValidate` using `TimeSlotUtils.candidateStarts(...).contains` + `isWithinHorizon` + vehicle precheck, `pickCandidate` with `TimeSlotUtils.localDay` bounds, pre-registered retry counters via `MeterRegistry`, `transactionTemplate.setTimeout(5)`).
6. `./mvnw verify` green; run `BookingConcurrencyIT` 5× (`./mvnw verify -Dit.test=BookingConcurrencyIT` loop) → no flake.

## Todo List
- [ ] `CandidateSelectionTest` red → green
- [ ] `BookingConcurrencyIT`, `VehicleOverlapIT`, `IdempotencyIT` red
- [ ] DTOs, `SqlErrorUtils`, `CandidateSelectionUtils`
- [ ] `AppointmentWriteRepository` fragment (JdbcClient, OffsetDateTime binds, SQL constants, vehicle precheck)
- [ ] `IdempotencyService`/`Impl` (MANDATORY, `findExisting` / `insertClaim`)
- [ ] `AppointmentService`/`Impl.book` whole-tx retry + retry counter
- [ ] `./mvnw verify` green, concurrency IT 5× stable

## Success Criteria
- `./mvnw verify` green: `CandidateSelectionTest`, `BookingConcurrencyIT` (bay- and tech-bound exact K), `VehicleOverlapIT`, `IdempotencyIT` pass.
- `./mvnw verify -Dit.test=BookingConcurrencyIT` passes 5 consecutive runs.

## Risk Assessment
- Concurrent conflicting inserts deadlock (40P01) by design → retried; if the deadlock share makes the burst slow, check `deadlock_timeout` is 100ms. `> K` successes are impossible (constraint); spurious NO_CAPACITY means the candidate SQL disagrees with the constraint → investigate before moving on.
- Spring may translate 23P01 to a different `DataAccessException` subtype → catching the base type + SQLState avoids dependence.
- Pool exhaustion in test (N + holder + audit) → pool 30; assert counts, never timings. Holder connections always in try-with-resources and every `future.get` bounded (10s) → a failing test cannot hang the suite.

## Security Considerations
- Idempotency key `[A-Za-z0-9_-]{1,128}` (validated at controller, phase 06); hash is server-computed so clients cannot forge replays.
- Date horizon (today + 90) bounds how far ahead anyone can occupy capacity; no rate limiting in V1 (out of scope).
- Constraint/SQL text never leaves the server: conflicts map to KUSS codes.

## Next Steps
- Phase 06 exposes `book` + cancel over HTTP; phase 07 adds `scheduler.booking.requests` metric.
