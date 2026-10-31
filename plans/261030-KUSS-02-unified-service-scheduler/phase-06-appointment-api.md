# Phase 06 — Appointment API + OpenAPI

## Context Links
- Plan: [plan.md](./plan.md) · Draft §7.3 (cancel), §8 (endpoints), §12 scenarios 1–11
- Phase 05 (`book`, idempotency, write fragment) · C-017 #7, #8

## Overview
- Priority: P1 · Status: Pending · Effort: 3h · Story: KUSS-04
- Commits: `[KUSS-04] Add appointment cancel and read service`, `[KUSS-04] Add appointment REST API and OpenAPI docs`
- HTTP surface for booking, cancel and appointment reads; springdoc as the client stub; end-to-end proof of §12 scenarios.

## Key Insights
- Both write endpoints return `AppointmentResultDto` → controller does `ResponseEntity.status(result.httpStatus()).body(result.appointment())`; replay and fresh responses share one path.
- Cancel races: lock the row (`JdbcClient` `SELECT … FOR UPDATE` in `AppointmentWriteRepository.findForUpdate`, returns `AppointmentDto`; no managed JPA entity on the mutating path, A-023 #2) before deciding → second canceller sees `CANCELLED` and returns current state; `UPDATE` always hits 1 row.
- Check order in cancel: not found → 404; already `CANCELLED` → 200 current state (even if in the past); `startTime ≤ clock.instant()` → 409 `KUSS_APPOINTMENT_ALREADY_STARTED`; else cancel.
- Constraint annotations on the `Idempotency-Key` param trigger Spring's built-in method validation → `HandlerMethodValidationException` (already mapped in phase 03).
- Past appointments cannot be booked through the API → seed them via `insertAppointment` (JDBC) for the 409 cancel test.
- HTTP tests prove the mapping (one case per status/code) plus the HTTP burst; booking semantics (in-flight harness, replay/mismatch variants) are proven once at service level in phase 05. <!-- Updated: Red Team #5 -->

## Requirements
| Endpoint | Success | Errors |
|---|---|---|
| `POST /api/v1/appointments` (header `Idempotency-Key`, body `BookAppointmentDto`) | 201 / replayed status | 400 VALIDATION / KEY_MISSING, 404, 409 NO_CAPACITY / VEHICLE / IN_FLIGHT, 422 |
| `GET /api/v1/appointments/{id}` | 200 | 404 |
| `POST /api/v1/appointments/{id}/cancel` (header `Idempotency-Key`) | 200 / replayed | 400 KEY_MISSING, 404, 409 ALREADY_STARTED / IN_FLIGHT, 422 |
| `GET /api/v1/dealerships/{id}/appointments?date=YYYY-MM-DD` | 200 (CONFIRMED + CANCELLED starting that local day, ordered by start) | 400, 404 |
- `Idempotency-Key`: `@Pattern(regexp = "[A-Za-z0-9_-]{1,128}")`; absent → `KUSS_IDEMPOTENCY_KEY_MISSING`, blank / too long / other characters → `KUSS_VALIDATION_FAILED`. <!-- Updated: Red Team #14 -->
- Cancel hash canonical string: `"POST /api/v1/appointments/" + id + "/cancel"`.
- OpenAPI: `/v3/api-docs`, Swagger UI `/swagger-ui.html`; `@OpenAPIDefinition(info = @Info(title = "Unified Service Scheduler API", version = "v1"))`.

## Architecture
```java
@Transactional
public AppointmentResultDto cancel(UUID appointmentId, String idempotencyKey) {
    Optional<AppointmentResultDto> replay = idempotencyService.claim(idempotencyKey, cancelRequest(appointmentId));
    if (replay.isPresent()) {
        return replay.get();
    }
    AppointmentDto appointment = appointmentRepository.findForUpdate(appointmentId)
            .orElseThrow(() -> new SchedulerException(ErrorCode.KUSS_RESOURCE_NOT_FOUND, ErrorMessageConstants.APPOINTMENT_NOT_FOUND));
    AppointmentResultDto result = AppointmentResultDto.builder().status(200).appointment(cancelIfConfirmed(appointment)).build();
    idempotencyService.complete(idempotencyKey, result);
    return result;
}

private AppointmentDto cancelIfConfirmed(AppointmentDto current) {
    if (!AppointmentStatus.CONFIRMED.name().equals(current.status())) {
        return current;
    }
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);   // µs = Postgres precision, replay body identical
    if (!current.startTime().isAfter(now)) {
        throw new SchedulerException(ErrorCode.KUSS_APPOINTMENT_ALREADY_STARTED, ErrorMessageConstants.APPOINTMENT_ALREADY_STARTED);
    }
    appointmentRepository.markCancelled(current.id(), now);   // JdbcClient UPDATE … WHERE id = :id AND status = 'CONFIRMED'
    return current.cancelled(now);
}
```
- `getAppointment(id)` and `listForDay(dealershipId, date)`: `@Transactional(readOnly = true)`; day bounds from `TimeSlotUtils.localDay(date, zone)`; dealership 404 first.
- `AppointmentWriteRepository` adds `Optional<AppointmentDto> findForUpdate(UUID id)` (`JdbcClient`, `SELECT … FROM appointment WHERE id = :id FOR UPDATE`) <!-- Updated: Cross-review A-023 #2 -->; `AppointmentRepository` adds `findByDealershipIdAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTime(...)` (read-only list).
- Controller has no logic: validate → delegate → map result.

## Related Code Files
`{root}` = `/Users/kangourouuu/orca/workspaces/keyloop-unified-service-scheduler/tilefish`. Create:
- `{root}/src/main/java/com/keyloop/scheduler/appointment/AppointmentController.java`
- `{root}/src/test/java/com/keyloop/scheduler/appointment/AppointmentApiIT.java`
Modify:
- `{root}/src/main/java/com/keyloop/scheduler/appointment/{AppointmentService,AppointmentServiceImpl}.java` (cancel, get, listForDay)
- `{root}/src/main/java/com/keyloop/scheduler/appointment/AppointmentRepository.java` (day query)
- `{root}/src/main/java/com/keyloop/scheduler/appointment/{AppointmentWriteRepository,AppointmentWriteRepositoryImpl}.java` (`findForUpdate`)
- `{root}/src/main/java/com/keyloop/scheduler/appointment/AppointmentDto.java` (`cancelled(Instant)`)
- `{root}/src/main/java/com/keyloop/scheduler/SchedulerApplication.java` (`@OpenAPIDefinition`)

## Implementation Steps
1. Tests first (red) — `AppointmentApiIT` over HTTP (`RestClient` from `IntegrationTestSupport`), London date+7 unless stated:
   - #1 validation: off-grid 09:15, yesterday, BRAKE_REPAIR at 17:00 (ends 18:30 > close), today + 91, start without offset, missing `vehicleId`, 129-char key, key `a b` → 400 `KUSS_VALIDATION_FAILED`
   - #2 missing header on POST book and on cancel → 400 `KUSS_IDEMPOTENCY_KEY_MISSING`
   - #3 unknown dealership / vehicle / service type on book, unknown id on GET + cancel, unknown dealership on list → 404 `KUSS_RESOURCE_NOT_FOUND`
   - #4 same vehicle overlapping, new key → 409 `KUSS_VEHICLE_ALREADY_BOOKED`
   - #5 3 OIL_CHANGE fill the slot → 4th 409 `KUSS_NO_CAPACITY`
   - #6 not duplicated here: `IdempotencyIT` (service-level lock-holder harness) + `SchedulerGlobalHandlerTest` (code → 409 mapping)
   - #7 `insertAppointment(…, now - 1h, CONFIRMED)` → cancel → 409 `KUSS_APPOINTMENT_ALREADY_STARTED`
   - #8 same key, other body → 422 `KUSS_IDEMPOTENCY_KEY_MISMATCH`
   - #9 not duplicated here: `ErrorHandlingIT` (response) + `RequestAuditIT` (audit row)
   - #10 replay with reformatted JSON (extra whitespace, reordered fields) → 201, identical body, 1 DB row (the only book-replay case; `+01:00`/`Z` variants stay in `IdempotencyIT`); cancel replay same key → 200 identical body; second cancel with new key → 200 status `CANCELLED`
   - #11 burst N=10 distinct vehicles + keys, OIL_CHANGE same slot → exactly 3×201, 7×409 NO_CAPACITY
   - cancel frees capacity: cancel one of the 3 → re-book same slot (new vehicle, new key) → 201
   - reads: GET by id returns booked row; list for the date returns CONFIRMED + CANCELLED ordered by start
   - OpenAPI: `GET /v3/api-docs` 200 and contains `/api/v1/appointments`
2. Implement repository additions, `AppointmentDto.cancelled`, service `cancel` / `getAppointment` / `listForDay`, `AppointmentController`, `@OpenAPIDefinition`.
3. `./mvnw verify` green; manual: Swagger UI loads, POST from UI works with header.

## Todo List
- [ ] `AppointmentApiIT` red (scenarios 1–5, 7, 8, 10, 11 + cancel/reads/OpenAPI)
- [ ] `findForUpdate` (JdbcClient) + day query
- [ ] Service cancel/get/list
- [ ] Controller with header + body validation
- [ ] `@OpenAPIDefinition`
- [ ] `./mvnw verify` green

## Success Criteria
- `./mvnw verify` green; `AppointmentApiIT` passes all listed cases.
- `curl -s localhost:8080/v3/api-docs | grep /api/v1/appointments` matches; `/swagger-ui.html` renders.

## Risk Assessment
- HTTP burst flakiness from client connection limits → `RestClient` on JDK `HttpClient` + fixed pool of 10 + start latch; assert counts only; `future.get(10, SECONDS)`.
- Method validation changing body-error exception type (`MethodArgumentNotValidException` vs `HandlerMethodValidationException`) → both already map to `KUSS_VALIDATION_FAILED`.

## Security Considerations
- No auth (draft §11 #9): anyone can list dealerships, list a day's appointments, read and cancel any appointment. Auth + per-dealership scoping is roadmap; no rate limiting in V1. <!-- Updated: Red Team #10 -->
- Appointment responses carry ids and times only (no customer name/contact). Idempotency key charset + length restricted.

## Next Steps
- Phase 07 audits these endpoints and adds booking request metrics.
