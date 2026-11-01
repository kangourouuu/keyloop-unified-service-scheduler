# Manual test results (clean reset run)

- Script: `docs/qa/manual-test-cases.md` (after the RD-05 expectation fix), sections 1 to 10 in file order, once, one bash session.
- Run time: Sun 2026-11-01 16:45:03 to 16:45:12 +07 (09:45 UTC)
- DATE = 2026-11-08 (today + 7; London `Z`, New York `-05:00`)
- Git SHA: 0396a74 (branch feature/KUSS-04-unified-service-scheduler)
- Server: http://localhost:8080, containers kuss04-bugfix-db-1 and kuss04-bugfix-app-1 up (podman ps)
- RESET (§0.1) printed `TRUNCATE TABLE` before section 1. Section header blocks were pasted once at the start of the same shell session (same `BASE`, `LONDON`, `NY`, `DATE`, `T`, `V`, `bk`, `cx`, `J`, `PSQL` definitions; later blocks only redefine identical values). Run with bash because zsh does not word-split `$PSQL`.
- Executor: `bash` script, all 114 runnable curl/psql cases executed; raw output kept outside the repo.

## Summary

| Section | Cases | PASS | FAIL | BLOCKED |
|---|---|---|---|---|
| 1 Catalog | 7 | 7 | 0 | 0 |
| 2 Availability | 12 | 12 | 0 | 0 |
| 3 Booking | 20 | 20 | 0 | 0 |
| 4 Idempotency | 12 | 12 | 0 | 0 |
| 5 Conflicts | 19 | 19 | 0 | 0 |
| 6 Vehicle | 6 | 6 | 0 | 0 |
| 7 Cancel | 11 | 11 | 0 | 0 |
| 8 Reads | 8 | 8 | 0 | 0 |
| 9 Errors/headers | 12 | 10 | 0 | 2 |
| 10 Docs/health | 9 | 9 | 0 | 0 |
| Total | 116 | 114 | 0 | 2 |

## FAIL

None.

## BLOCKED

- EH-11 `Retry-After: 1` on `503 KUSS_SERVICE_BUSY`: BLOCKED, ENVIRONMENT. The script marks it NOTE only: needs pool exhaustion or a statement timeout, not triggerable with curl. Covered by integration tests.
- EH-12 `500 KUSS_INTERNAL_ERROR`: BLOCKED, ENVIRONMENT. The script marks it NOTE only: no manual trigger. Covered by integration tests.

## Per case

### 1 Catalog
- CT-01 PASS: 200, 2 dealerships, London 08:00-18:00, NY 08:00-17:00
- CT-02 PASS: 4 bays (BAY-04 false), 5 techs, anyActive false, allIsActive true
- CT-03 PASS: BAY-01/02, Tech 01 four skills, Tech 02 OIL_CHANGE
- CT-04 PASS: 404 KUSS_RESOURCE_NOT_FOUND "Dealership not found."
- CT-05 PASS: 4 service types 90/120/30/60
- CT-06 PASS: n=3, v=[8,8,8], keys as expected
- CT-07 PASS: 400 KUSS_VALIDATION_FAILED "Malformed request."

### 2 Availability
- AV-01 PASS: n=20, first 08:00Z cap 3, last 17:30Z cap 3
- AV-02 PASS: n=17, 08:00-05:00 to 16:00-05:00, cap 1
- AV-03 PASS: n=17, last 16:00Z, cap 1
- AV-04 PASS: n=18, last 16:30Z, cap 2
- AV-05 PASS: n=19, last 17:00Z, cap 2
- AV-06 PASS: HTTP 200 for today
- AV-07 PASS: 400 "Date is in the past."
- AV-08 PASS: 200, then 400 "Date is more than 90 days ahead."
- AV-09 PASS: 400 "Invalid request.", 400 "Malformed request."
- AV-10 PASS: 400 x3, blank and 41-char details as expected
- AV-11 PASS: 404 service type, 404 dealership
- AV-12 PASS: 09:30 cap 3, 10:00 cap 2, 10:30 cap 3

### 3 Booking
- BK-01 PASS: 201 CONFIRMED 10:00Z-10:30Z, bay 103, tech 104, customer ...0001, cancelledAt null
- BK-02 PASS: 201 13:00Z-13:30Z, bay 201, tech 202, customer ...0002
- BK-03 PASS: 201 ends 18:00Z, customer ...0003
- BK-04 PASS: 400 "Start time is not a bookable slot."
- BK-05 PASS: 201 ends 18:00Z, tech 103
- BK-06 PASS: 400 KUSS_VALIDATION_FAILED
- BK-07 PASS: 400 KUSS_VALIDATION_FAILED
- BK-08 PASS: 400 KUSS_VALIDATION_FAILED
- BK-09 PASS: 400 KUSS_VALIDATION_FAILED (yesterday)
- BK-10 PASS: 201 on 2027-01-30 (today+90)
- BK-11 PASS: 400 "Date is more than 90 days ahead."
- BK-12 PASS: 404 "Vehicle not found."
- BK-13 PASS: 404 "Dealership not found."
- BK-14 PASS: 404 "Service type not found."
- BK-15 PASS: 400, detail names all four fields
- BK-16 PASS: 400 "Malformed request."
- BK-17 PASS: 400 "Malformed request."
- BK-18 PASS: 400 "serviceTypeCode: size must be between 0 and 40"
- BK-19 PASS: 415 KUSS_VALIDATION_FAILED
- BK-20 PASS: 400 "Malformed request."

### 4 Idempotency
- ID-01 PASS: 400 KUSS_IDEMPOTENCY_KEY_MISSING
- ID-02 PASS: 400 KUSS_VALIDATION_FAILED
- ID-03 PASS: 400 KUSS_VALIDATION_FAILED (129 chars)
- ID-04 PASS: 201 (128 chars)
- ID-05 PASS: 201 twice, SAME_ID, day list count 1
- ID-06 PASS: 422 KUSS_IDEMPOTENCY_KEY_MISMATCH
- ID-07 PASS: 422 KUSS_IDEMPOTENCY_KEY_MISMATCH
- ID-08 PASS: cancel 422 MISMATCH, GET CONFIRMED
- ID-09 PASS: 404 then 201
- ID-10 PASS: 409 KUSS_IDEMPOTENCY_IN_FLIGHT (psql insert/delete ran)
- ID-11 PASS: one 409 IN_FLIGHT, one 201, V10 count 1
- ID-12 PASS: 201 ID1, replay same id, cancel 200 CANCELLED, 23 h returns ID1, 25 h fresh id ca45ac36... differs from ID1

### 5 Conflicts
- CF-01 PASS: 201 x3, bays 102/101/103, techs 102/101/104
- CF-02 PASS: 409 KUSS_NO_CAPACITY, detail as expected
- CF-03 PASS: 409 KUSS_NO_CAPACITY
- CF-04 PASS: 201 ends 15:00Z
- CF-05 PASS: 201 ends 16:30Z
- CF-06 PASS: cancel 200 CANCELLED, then 201
- CF-07 PASS: 201 tech 105, ends 12:00Z
- CF-08 PASS: 409 KUSS_NO_CAPACITY
- CF-09 PASS: 409 KUSS_NO_CAPACITY
- CF-10 PASS: 409 KUSS_NO_CAPACITY
- CF-11 PASS: 201 tech 105, ends 14:00Z
- CF-12 PASS: 201 tech 103
- CF-13 PASS: 201 tech 105
- CF-14 PASS: 201 tech 201, 15:00Z-16:00Z
- CF-15 PASS: 201 tech 202, bay 202 vs BAYX 201
- CF-16 PASS: 409 KUSS_NO_CAPACITY
- CF-17 PASS: 201 bay 202 = BAYY, tech 202
- CF-18 PASS: 409 KUSS_NO_CAPACITY
- CF-19 PASS: 201 tech 201

### 6 Vehicle
- VH-01 PASS: 201
- VH-02 PASS: 201 ends 18:30Z
- VH-03 PASS: 409 KUSS_VEHICLE_ALREADY_BOOKED
- VH-04 PASS: 409 KUSS_VEHICLE_ALREADY_BOOKED
- VH-05 PASS: 201
- VH-06 PASS: 409 KUSS_VEHICLE_ALREADY_BOOKED

### 7 Cancel
- CN-01 PASS: 201, cancel 200 CANCELLED, cancelledAt set
- CN-02 PASS: 200 CANCELLED
- CN-03 PASS: day list entry CANCELLED
- CN-04 PASS: 200 identical body, same cancelledAt
- CN-05 PASS: 200 same cancelledAt
- CN-06 PASS: 404 "Appointment not found."
- CN-07 PASS: 400 KUSS_IDEMPOTENCY_KEY_MISSING
- CN-08 PASS: 400 KUSS_VALIDATION_FAILED
- CN-09 PASS: 409 KUSS_APPOINTMENT_ALREADY_STARTED, GET CONFIRMED
- CN-10 PASS: 201, 201, availability filter `[]`
- CN-11 PASS: 422 MISMATCH, V23 stays CONFIRMED

### 8 Reads
- RD-01 PASS: 200, same fields as BK-01
- RD-02 PASS: 404 KUSS_RESOURCE_NOT_FOUND
- RD-03 PASS: 400 KUSS_VALIDATION_FAILED
- RD-04 PASS: n=19, sorted true
- RD-05 PASS: -365 -> 0; -366 -> 400 "Date is more than 365 days back."; +90 -> 1 (after BK-10); +91 -> 400 "Date is more than 90 days ahead."
- RD-06 PASS: contains 13:00Z (BK-02) and the CN/VH/CF NY bookings
- RD-07 PASS: 404 KUSS_RESOURCE_NOT_FOUND
- RD-08 PASS: 400 KUSS_VALIDATION_FAILED

### 9 Errors and headers
- EH-01 PASS: UUID X-Request-Id on success
- EH-02 PASS: body requestId equals header
- EH-03 PASS: two fresh UUIDs, neither `my-own-id`
- EH-04 PASS: application/problem+json, keys detail, instance, status, title, code, requestId (no `type`)
- EH-05 PASS: 405 twice, KUSS_VALIDATION_FAILED "Method not allowed."
- EH-06 PASS: 415 (see BK-19)
- EH-07 PASS: 404 "Resource not found."
- EH-08 PASS: CLEAN
- EH-09 PASS: 400 "Invalid request.", no requestId, no instance, no X-Request-Id
- EH-10 PASS: after 5 s, last 10 audit rows match the suite requests with http_status and error_code (total rows 338)
- EH-11 BLOCKED: see BLOCKED
- EH-12 BLOCKED: see BLOCKED

### 10 Documentation and health
- DH-01 PASS: `302 http://localhost:8080/swagger-ui/index.html`, then 200
- DH-02 PASS: openapi 3.1.0, 10 paths incl. `/error`, Idempotency-Key header required
- DH-03 PASS: 200 `{"groups":["liveness","readiness"],"status":"UP"}`
- DH-04 PASS: 200 twice (curl script prints the second body `{"status":"UP"}` before the second code because `-o` applies to the first URL only; both codes 200)
- DH-05 PASS: 200
- DH-06 PASS: the four scheduler metric families present
- DH-07 PASS: 404
- DH-08 PASS: count 0
- DH-09 PASS: steps run as curl: availability 11:30Z cap 2, 201 CONFIRMED, GET 200, cancel 200 CANCELLED, availability cap 2 again

## Notes

- No script or expected value was changed. No FAIL, so no EXPECTATION ERROR or PRODUCT DEFECT to report.
- The server was left running; the database holds this run's bookings (no second RESET).
