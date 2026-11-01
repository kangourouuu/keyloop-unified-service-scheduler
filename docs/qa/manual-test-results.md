# Manual test results: Unified Service Scheduler (Scenario A)

- Run: 2026-11-01 08:30-08:31 UTC (one pass, one RESET, sections 1-10 in file order; section 11 not run)
- DATE used: 2026-11-08 (today + 7 days; London `Z`, New York `-05:00`, inside the 2026-11-08..2027-03-06 window of §0.2)
- Git SHA: f780067 (branch feature/KUSS-04-unified-service-scheduler)
- Server: http://localhost:8080, Podman containers `kuss04-bugfix-db-1` (healthy) and `kuss04-bugfix-app-1` up before and after the run
- Shell: bash, section header blocks sourced before each section; `ID_BK01`, `IDA`, `A1`, `BAYX/BAYY`, `ID22`, `ID23` carried across sections as the cases require
- Expected values were not edited. AV-12 was run right after BK-01 as the case says.

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
| 8 Reads | 8 | 7 | 1 | 0 |
| 9 Errors/headers | 12 | 10 | 0 | 2 |
| 10 Docs/health | 9 | 9 | 0 | 0 |
| Total | 116 | 113 | 1 | 2 |

## FAIL and BLOCKED

### RD-05 FAIL, EXPECTATION ERROR
- Case expects `+90 -> 0`. Actual: `{"o":"+90","r":1}`. Other three lines match (`-365 -> 0`, `-366 -> 400 "Date is more than 365 days back."`, `+91 -> 400 "Date is more than 90 days ahead."`).
- Cause: BK-10 (section 3, same run) books London OIL_CHANGE on today+90 (2027-01-30T10:00Z, id a3e9a48d-33d9-4ce2-8a13-b89def18a17b), so the London day list for +90 holds that row. The server is correct. The case text conflicts with BK-10; correct value is `1` after BK-10 (or `0` only when run without BK-10). Horizon logic: TimeSlotUtils.java:21-22, 73, 93 (per §11).
- No product defect.

### EH-11 BLOCKED, ENVIRONMENT
- Marked NOTE only in the script: `503 KUSS_SERVICE_BUSY` with `Retry-After: 1` needs pool exhaustion or a statement timeout; not triggerable by curl against this server. Covered by integration tests per the script.

### EH-12 BLOCKED, ENVIRONMENT
- Marked NOTE only: `500 KUSS_INTERNAL_ERROR` not triggerable manually.

## Observations (not failures)

- BK-17 (no offset) returned 400 `KUSS_VALIDATION_FAILED`, `detail="Malformed request."`; the open question in §11 is resolved (no 201).
- DH-09 is Swagger-only; run here as the equivalent curl sequence (availability 11:30Z cap 2 -> POST 201 -> GET 200 CONFIRMED -> cancel 200 CANCELLED -> availability cap 2). Swagger UI itself was not driven; the UI loads (DH-01).
- AV-12 / CN-10 / RD-06 depend on earlier bookings and behaved as written.
- ID-11 outcome: one 201, the other 409 `KUSS_IDEMPOTENCY_IN_FLIGHT`; V10 day-list count 1.
- EH-08 sample clean; the 400/409 `detail` strings seen in sections 3-7 contain no exception, SQL or constraint text.
- EH-10: 197 audit rows at check time, none with status >= 400 and null `error_code`; DH-08 count of actuator/swagger/v3 rows is 0.

## Cases run (ID, result, actual status and key fields)

### 1 Catalog
- CT-01 PASS 200; 2 dealerships, London Europe/London 08:00-18:00, NY America/New_York 08:00-17:00
- CT-02 PASS 200; BAY-04 isActive false, 5 techs with expected skills; anyActive false, allIsActive true
- CT-03 PASS 200; BAY-01/02; Tech 01 four skills, Tech 02 OIL_CHANGE
- CT-04 PASS 404 KUSS_RESOURCE_NOT_FOUND "Dealership not found.", requestId present
- CT-05 PASS 200; BRAKE 90, ENGINE 120, OIL 30, TIRE 60
- CT-06 PASS 200; n=3, v=[8,8,8], keys as expected
- CT-07 PASS 400 KUSS_VALIDATION_FAILED "Malformed request."

### 2 Availability
- AV-01 PASS 200 Europe/London n=20 first 08:00Z cap 3, last 17:30Z cap 3
- AV-02 PASS 200 America/New_York n=17 first 08:00-05:00, last 16:00-05:00, cap 1
- AV-03 PASS 200 n=17 last 16:00Z cap 1
- AV-04 PASS 200 n=18 last 16:30Z cap 2
- AV-05 PASS 200 n=19 last 17:00Z cap 2
- AV-06 PASS 200; 18 slots, first 09:00Z (clock 08:30Z), only future slots
- AV-07 PASS 400 "Date is in the past."
- AV-08 PASS 200 then 400 "Date is more than 90 days ahead."
- AV-09 PASS 400 "Invalid request." then 400 "Malformed request."
- AV-10 PASS 400 x3; blank "serviceType: must not be blank"; long "serviceType: size must be between 0 and 40"
- AV-11 PASS 404 "Service type not found." / "Dealership not found."
- AV-12 PASS 09:30 cap 3, 10:00 cap 2, 10:30 cap 3

### 3 Booking
- BK-01 PASS 201 CONFIRMED 10:00Z-10:30Z, customer ...0004-...001, bay 101, tech 102, cancelledAt null, X-Request-Id present
- BK-02 PASS 201 13:00Z-13:30Z, customer ...002, bay 201, tech 201
- BK-03 PASS 201 end 18:00Z, customer ...003
- BK-04 PASS 400 "Start time is not a bookable slot."
- BK-05 PASS 201 end 18:00Z, tech 103
- BK-06 PASS 400 KUSS_VALIDATION_FAILED
- BK-07 PASS 400 KUSS_VALIDATION_FAILED
- BK-08 PASS 400 KUSS_VALIDATION_FAILED
- BK-09 PASS 400 KUSS_VALIDATION_FAILED
- BK-10 PASS 201 startTime 2027-01-30 (today+90)
- BK-11 PASS 400 "Date is more than 90 days ahead."
- BK-12 PASS 404 "Vehicle not found."
- BK-13 PASS 404 "Dealership not found."
- BK-14 PASS 404 "Service type not found."
- BK-15 PASS 400; detail names desiredStartTime, serviceTypeCode, vehicleId, dealershipId
- BK-16 PASS 400 "Malformed request."
- BK-17 PASS 400 "Malformed request."
- BK-18 PASS 400 "serviceTypeCode: size must be between 0 and 40"
- BK-19 PASS 415 KUSS_VALIDATION_FAILED
- BK-20 PASS 400 "Malformed request."

### 4 Idempotency
- ID-01 PASS 400 KUSS_IDEMPOTENCY_KEY_MISSING
- ID-02 PASS 400 KUSS_VALIDATION_FAILED (key pattern)
- ID-03 PASS 400 KUSS_VALIDATION_FAILED (129 chars)
- ID-04 PASS 201 (128 chars)
- ID-05 PASS 201 twice, same id e7e3e985..., SAME_ID, day list count 1
- ID-06 PASS 422 KUSS_IDEMPOTENCY_KEY_MISMATCH
- ID-07 PASS 422 KUSS_IDEMPOTENCY_KEY_MISMATCH
- ID-08 PASS 422 KUSS_IDEMPOTENCY_KEY_MISMATCH; GET status CONFIRMED
- ID-09 PASS 404 then 201
- ID-10 PASS 409 KUSS_IDEMPOTENCY_IN_FLIGHT (fixture row inserted and deleted)
- ID-11 PASS one 201, one 409 IN_FLIGHT; V10 count 1
- ID-12 PASS 201 id ba4d0651; replay same id; cancel 200 CANCELLED; 23h replay returns same id with status CONFIRMED; 25h fresh 201 id fdeaf8d4 differs

### 5 Conflicts
- CF-01 PASS 201 x3; bays 102/101/103, techs 101/104/102
- CF-02 PASS 409 KUSS_NO_CAPACITY "No bay and technician are free for this slot."
- CF-03 PASS 409 KUSS_NO_CAPACITY
- CF-04 PASS 201 end 15:00Z
- CF-05 PASS 201 end 16:30Z
- CF-06 PASS cancel 200 CANCELLED; rebook with case-cf-02 201
- CF-07 PASS 201 tech 105, end 12:00Z
- CF-08 PASS 409 KUSS_NO_CAPACITY
- CF-09 PASS 409 KUSS_NO_CAPACITY
- CF-10 PASS 409 KUSS_NO_CAPACITY
- CF-11 PASS 201 tech 105, end 14:00Z
- CF-12 PASS 201 tech 103
- CF-13 PASS 201 tech 105
- CF-14 PASS 201 tech 201, 15:00Z-16:00Z, bay 202
- CF-15 PASS 201 tech 202, bay 201 (differs from 202)
- CF-16 PASS 409 KUSS_NO_CAPACITY
- CF-17 PASS 201 bay 201 = BAYY, tech 202
- CF-18 PASS 409 KUSS_NO_CAPACITY
- CF-19 PASS 201 tech 201

### 6 Vehicle
- VH-01 PASS 201
- VH-02 PASS 201 end 18:30Z
- VH-03 PASS 409 KUSS_VEHICLE_ALREADY_BOOKED
- VH-04 PASS 409 KUSS_VEHICLE_ALREADY_BOOKED
- VH-05 PASS 201
- VH-06 PASS 409 KUSS_VEHICLE_ALREADY_BOOKED

### 7 Cancel
- CN-01 PASS booking 201; cancel 200 CANCELLED, cancelledAt set
- CN-02 PASS 200 CANCELLED
- CN-03 PASS day list entry CANCELLED
- CN-04 PASS 200, identical cancelledAt
- CN-05 PASS 200, same cancelledAt
- CN-06 PASS 404 "Appointment not found."
- CN-07 PASS 400 KUSS_IDEMPOTENCY_KEY_MISSING
- CN-08 PASS 400 KUSS_VALIDATION_FAILED
- CN-09 PASS 409 KUSS_APPOINTMENT_ALREADY_STARTED "Appointment has already started."; GET CONFIRMED
- CN-10 PASS 201, 201, availability filter `[]`
- CN-11 PASS 422 KUSS_IDEMPOTENCY_KEY_MISMATCH; V23 stays CONFIRMED

### 8 Reads
- RD-01 PASS 200 same fields as BK-01 body
- RD-02 PASS 404 KUSS_RESOURCE_NOT_FOUND
- RD-03 PASS 400 KUSS_VALIDATION_FAILED
- RD-04 PASS 200 n=19 sorted true
- RD-05 FAIL (EXPECTATION ERROR) see above
- RD-06 PASS 200; contains 13:00Z (BK-02) and the NY bookings
- RD-07 PASS 404 KUSS_RESOURCE_NOT_FOUND
- RD-08 PASS 400 KUSS_VALIDATION_FAILED

### 9 Errors and headers
- EH-01 PASS X-Request-Id UUID on 200
- EH-02 PASS requestId equals header (4e61236d...)
- EH-03 PASS two different server UUIDs, `my-own-id` ignored
- EH-04 PASS application/problem+json; keys code, detail, instance, requestId, status, title
- EH-05 PASS 405 x2, KUSS_VALIDATION_FAILED "Method not allowed."
- EH-06 PASS 415 (same evidence as BK-19)
- EH-07 PASS 404 KUSS_RESOURCE_NOT_FOUND "Resource not found."
- EH-08 PASS CLEAN
- EH-09 PASS 400 KUSS_VALIDATION_FAILED "Invalid request."; body has type about:blank, no requestId, no instance; no X-Request-Id header
- EH-10 PASS audit rows with http_status and KUSS error_code per request
- EH-11 BLOCKED (not triggerable)
- EH-12 BLOCKED (not triggerable)

### 10 Docs and health
- DH-01 PASS 302 to /swagger-ui/index.html, then 200
- DH-02 PASS openapi 3.1.0; 9 paths plus /error; Idempotency-Key header required
- DH-03 PASS 200 {"groups":["liveness","readiness"],"status":"UP"}
- DH-04 PASS 200 x2
- DH-05 PASS 200
- DH-06 PASS 4 scheduler_* metric families
- DH-07 PASS 404
- DH-08 PASS count 0
- DH-09 PASS (curl equivalent, Swagger UI not driven) 201 -> 200 -> cancel 200 -> availability back to capacity 2
