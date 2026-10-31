# KUSS-05 retest report and bug list

Retest of the implemented Unified Service Scheduler (Scenario A) at `99d0056`, branch `feature/KUSS-05-retest-bug-report`. No production code was changed by this story. The branch is now rebased onto the KUSS-04 tip `0cc274f`, which carries the fixes for BUG-1 to BUG-6; the statuses below were re-checked against that code and a full `./mvnw verify` run. BUG-7 is open (roadmap).

## 1. Summary

| Item | Result |
|---|---|
| Build | `./mvnw verify` BUILD SUCCESS in 3 of 3 runs on `99d0056` (27 s, 18 s, 27 s); 0 Checkstyle violations; Spotless clean. After the rebase onto `0cc274f`: BUILD SUCCESS in 1 run (1 min 22 s) |
| Tests at `99d0056` | 45 unit (Surefire) + 76 integration (Failsafe) = 121, 0 failures, 0 errors, 0 skipped, in every run |
| Tests after this story | 45 unit + 83 integration = 128 (7 new in `BookingEdgeCasesIT`), green in 2 further full runs |
| Tests after the rebase onto `0cc274f` | 48 unit (Surefire) + 98 integration (Failsafe) = 146, 0 failures, 0 errors, 0 skipped. This includes the 7 `BookingEdgeCasesIT` tests and the tests KUSS-04 added for the BUG-1 to BUG-6 fixes |
| Flaky tests | None seen in 6 full runs (3 at `99d0056`, 2 with the new tests, 1 after the rebase) and 1 targeted run of the new class. `40P01` deadlock WARN lines appear in the logs (17, 0 and 34 per run); they are retried by design and never failed a test |
| Bugs | **0 Critical, 0 High, 1 Medium, 6 Low** (7 total) when found at `99d0056`. Now: **BUG-1 to BUG-6 fixed** in KUSS-04, **BUG-7 open** (roadmap) |
| Double-booking | Not reproduced. 7 concurrent bursts (N=24 against K=3, 3, 3, 2, 1 free pairs, plus same-vehicle and same-key bursts) gave exactly K x 201, and a SQL overlap query over all rows returned 0 |

Bugs by severity (status after the KUSS-04 fixes, see section 5 for the evidence):

- Medium
  - BUG-1 (fixed): overload outside `POST /appointments` returns 500, not 503 `KUSS_SERVICE_BUSY`
- Low
  - BUG-2 (fixed): a database lock stall in booking returns 500 with an ERROR stack trace, and reads wait without bound
  - BUG-3 (fixed): the 400 `detail` for `POST /appointments` names the parameter `request`, not the field
  - BUG-4 (fixed): `scheduler.booking.requests` never counts `KUSS_IDEMPOTENCY_KEY_MISSING` or boundary validation 400s
  - BUG-5 (fixed): `GET /error` returns a non-ProblemDetail 500 body (`status: 999`) and Tomcat-level errors return HTML
  - BUG-6 (fixed): `GET /actuator` (the index) is written to `request_audit`
  - BUG-7 (open, roadmap): there is no request-body size limit (a 60 MB JSON body was accepted and booked)

Already known findings from `plans/261030-KUSS-02-unified-service-scheduler/reports/review-261031-kuss04-alignment.md` (L-1 completed duplicate gets 409 IN_FLIGHT, L-2 key lives 24-48 h, L-4 backoff base, L-7, L-8, H-1, M-1, M-2, L-3, L-5, L-6) are not re-reported. DD-1, DD-2, F-1, F-2, F-5, F-6 and F-7 are present in the code at `99d0056` and still at `0cc274f` (see O-14).

## 2. Environment

| Item | Value |
|---|---|
| Code | `99d0056` plus `ef0232a` (tests only) for the black-box retest. Rebased onto KUSS-04 tip `0cc274f` (the fixes for BUG-1 to BUG-6 are in it) for the status re-check |
| Host | macOS (Darwin 27.2.0), Podman machine (libkrun, 4 CPU, 3.7 GiB) |
| JDK | OpenJDK 21.0.13 (`/opt/homebrew/opt/openjdk@21`) |
| Stack | Spring Boot 4.0.9, PostgreSQL 17 (`docker.io/library/postgres:17`, `deadlock_timeout=100ms`), Flyway V1 and V2 |
| Build | `./mvnw -B verify` with `kuss-env.sh` (Testcontainers over the Podman socket) |
| Black-box | `podman compose up --build -d`, app on `localhost:8080` with the `prod` profile (pool 20, connection timeout 3 s, ECS JSON logs), curl, `xargs -P`, `psql` through `compose exec db`; `podman compose down -v` run afterwards |
| Date used | 2026-10-31 (container clock UTC). London was on GMT; New York falls back on 2026-11-01, which gave a live DST edge inside the booking horizon |
| Seed | London: 3 active bays (+1 inactive), 5 technicians. New York: 2 bays, 2 technicians. 24 vehicles. `BRAKE_REPAIR`: London techs 01 and 03 only. `ENGINE_DIAGNOSTIC`: London tech 05 only |

## 3. Method

Cases were derived from the Scenario A PDF (requirements R1-R3), the draft section 12 mandatory scenarios (M-1..M-11) and the developer decisions (DD-1, DD-2 and the 2026-10-30/31 decisions). Each case is traced to an existing test, or marked GAP. GAP cases where the behavior was correct were turned into tests (`BookingEdgeCasesIT`, commit `ef0232a`).

The "Actual" column of section 4 is what the black-box run saw at `99d0056`, before the fixes. The "Status" column was updated after the rebase from the code and the green `verify` run; a row whose bug is fixed reads `FAIL at 99d0056, fixed (BUG-N)`. The black-box repros were not re-run against the fixed build; the tests named per bug in section 5 are the evidence.

Status values: **PASS** (behavior as expected), **FAIL** (a BUG is linked), **GAP-covered-now** (behavior correct, no test existed, test added in this story).

Black-box evidence was taken with curl against the running app, and against the database with `psql`. "BB" in the coverage column means exercised black-box.

## 4. Test-case table

### 4.1 Booking, availability and Scenario A requirements

| ID | Source | Steps | Expected | Actual | Status | Existing coverage |
|---|---|---|---|---|---|---|
| TC-01 | PDF R1 | `POST /appointments` for seed vehicle, `OIL_CHANGE`, London 10:00 | 201 `CONFIRMED`, bay and technician assigned | 201 | PASS | `AppointmentApiIT`, BB |
| TC-02 | PDF R3 | Read the stored booking | customer, vehicle, technician, bay all linked | linked; customer derived from vehicle | PASS | `BookingConcurrencyIT.should_link_customer_vehicle_technician_and_bay_on_the_stored_booking` |
| TC-03 | PDF R2, M-5 | Book 3 oil changes at 10:00, then a 4th vehicle | 409 `KUSS_NO_CAPACITY` | 409 | PASS | `AppointmentApiIT.should_return_409_no_capacity_when_the_fourth_oil_change_hits_a_full_slot`, BB |
| TC-04 | PDF R2 | 3 bays busy, book `BRAKE_REPAIR` at the same time (qualified technicians busy) | 409 `KUSS_NO_CAPACITY` | 409 | PASS | `BookingConcurrencyIT.should_book_exactly_2_technicians...`, BB |
| TC-05 | PDF R2 | `GET availability` after bookings | full slot omitted, others keep capacity | 10:00 omitted, 09:30 and 10:30 at capacity 3 | PASS | `AvailabilityApiIT.should_lower_capacity_for_a_booked_technician...`, BB |
| TC-06 | Draft 7.1 | Availability for today | slots before now are dropped | first slot 10:00Z at 09:58Z | PASS | `TimeSlotUtilsTest.should_drop_starts_before_now_on_the_same_day`, BB |
| TC-07 | Draft 4, 6.2 | Book 09:30 oil then overlapping 10:00 booking on the same vehicle | `[)` ranges: back-to-back allowed, overlap refused | back-to-back 201; overlap 409 `KUSS_VEHICLE_ALREADY_BOOKED` | PASS | `SchemaConstraintsIT.should_allow_back_to_back_bookings...`, BB |
| TC-08 | Draft 7.2 | Back-to-back oil changes for the same vehicle over HTTP (10:00 and 10:30) | both 201 | 201, 201 | GAP-covered-now | `BookingEdgeCasesIT.should_book_back_to_back_services_for_the_same_vehicle` |
| TC-09 | Draft 11 #5 | `BRAKE_REPAIR` (90 min) at 16:30 and `OIL_CHANGE` at 17:30 (end exactly 18:00 close) | 201, `endTime` = 18:00 | 201 for both | GAP-covered-now | `BookingEdgeCasesIT.should_book_the_last_slots_that_end_exactly_at_closing_time` (unit-level only before: `TimeSlotUtilsTest.should_accept_a_start_only_when_it_is_a_grid_start_that_ends_by_close`) |
| TC-10 | Draft 11 #5, M-1 | Brake at 17:00 (ends 18:30), oil at 18:00, oil at 07:30 | 400 `KUSS_VALIDATION_FAILED` | 400 | PASS | `AppointmentApiIT.should_return_400_validation_failed_for_bad_booking_input` (brake 17:00), BB |
| TC-11 | Draft 11 #4, M-1 | Start at `:15`, `:00:30`, `.000001`s | 400 | 400 | PASS | `AppointmentApiIT` (`:15`), BB |
| TC-12 | Draft 11 #6 | Same vehicle at London and New York, same instant | 409 `KUSS_VEHICLE_ALREADY_BOOKED` | 201 for a New York window that does not overlap the London booking; the overlapping case is covered by the test | PASS | `VehicleOverlapIT.should_reject_the_same_vehicle_at_another_dealership_for_the_same_instant`, BB |
| TC-13 | RT-8 | Vehicle booked and slot full | vehicle code wins over capacity | `KUSS_VEHICLE_ALREADY_BOOKED` | PASS | `VehicleOverlapIT.should_report_vehicle_conflict_rather_than_no_capacity...`, BB |
| TC-14 | Draft 7.1 | Availability and booking in London with offsets (`+07:00`, `Z`, `+00:00`) | the same instant is the same slot | accepted, normalized | PASS | `IdempotencyIT.should_replay_when_the_same_instant_is_sent_with_another_offset`, BB |
| TC-15 | Draft 11 #4 | New York 2026-11-01 (DST fall-back day): 08:00 at `-05:00`, 08:00 at `-04:00`, 13:00Z, 16:30 and 17:00 `-05:00` | 08:00 EST bookable; `-04:00` (07:00 EST) refused; 16:30 ok; 17:00 refused | 201, 400, 201, 201, 400 | PASS | `TimeSlotUtilsTest.should_shift_new_york_opening_when_daylight_saving_starts` (unit), BB (HTTP) |
| TC-16 | Draft 7.1 | Availability slot times for New York on the DST day | slot offsets are `-05:00` | `2026-11-01T08:00:00-05:00` | PASS | `AvailabilityApiIT.should_keep_the_local_offset_on_slot_start_times...` (not on a DST day), BB |
| TC-17 | Plan, RT-13b | Book and availability at 90 and 91 days ahead; list from 365 days back | 90 ok, 91 refused; list 365 back ok, 366 refused | availability 2027-01-29 ok, 01-30 400; list 2025-10-31 ok, 10-30 400 | PASS | `AvailabilityApiIT.should_return_400_when_date_is_beyond_the_90_day_horizon`, `AppointmentApiIT.should_list_a_day_up_to_365_days_back_and_90_days_ahead` |
| TC-18 | F-7 | Dates `+999999999-12-31` and `-999999999-01-01` on availability, list and booking | 400, never 500 | 400 | PASS | `AvailabilityApiIT.should_return_400_for_a_date_beyond_the_postgres_range`, BB |
| TC-19 | Draft 11 #8 | Unknown dealership, vehicle, service type; lowercase service type | 404 `KUSS_RESOURCE_NOT_FOUND` | 404 (lowercase is also 404; codes are case-sensitive) | PASS | `AppointmentApiIT.should_return_404_for_unknown_dealership_vehicle_service_type_and_appointment`, BB |

### 4.2 Idempotency

| ID | Source | Steps | Expected | Actual | Status | Existing coverage |
|---|---|---|---|---|---|---|
| TC-20 | M-2 | `POST` book and cancel without `Idempotency-Key` | 400 `KUSS_IDEMPOTENCY_KEY_MISSING` | 400, also for an empty header value | PASS | `AppointmentApiIT.should_return_400_idempotency_key_missing_on_book_and_cancel`, BB |
| TC-21 | M-10 | Same key, same payload, 3 times (also offsets `+00:00` and `+05:00`) | same 201 body, one row | identical body and `id`, 1 row | PASS | `IdempotencyIT.should_replay_the_stored_result_for_the_same_key_and_request`, BB |
| TC-22 | M-8 | Same key, different vehicle, then different service type | 422 `KUSS_IDEMPOTENCY_KEY_MISMATCH` | 422 | PASS | `AppointmentApiIT.should_return_422_when_the_same_key_carries_another_payload`, BB |
| TC-23 | Draft 7.3 | Book key reused for the cancel of that appointment | 422 | 422, appointment still `CONFIRMED` | GAP-covered-now | `BookingEdgeCasesIT.should_reject_a_booking_key_reused_to_cancel_as_422` |
| TC-24 | Draft 7.3 | Cancel key reused for the cancel of another appointment | 422, second appointment untouched | 422 | GAP-covered-now | `BookingEdgeCasesIT.should_reject_a_cancel_key_reused_for_another_appointment_as_422` |
| TC-25 | Draft 7.3 | Cancel replay with same key | same 200 | same 200 | PASS | `AppointmentApiIT.should_replay_cancel_and_report_cancelled_for_a_new_key_on_a_cancelled_appointment`, BB |
| TC-26 | M-6 | 12 parallel requests, same key, same payload | exactly 1 appointment; others 409 `KUSS_IDEMPOTENCY_IN_FLIGHT` (or a replay of the same `id`) | 1 x 201, 11 x 409; 1 row | GAP-covered-now | `BookingEdgeCasesIT.should_create_one_appointment_when_parallel_requests_share_an_idempotency_key` (service-level only before: `IdempotencyIT.should_report_in_flight_when_the_blocking_claim_commits`) |
| TC-27 | Plan (key charset) | Keys of 128 chars, 129 chars, with a space, with `'` | 128 ok; the others 400 `KUSS_VALIDATION_FAILED` | as expected | GAP-covered-now (128 ok) | `BookingEdgeCasesIT.should_accept_an_idempotency_key_of_128_characters`; 129 and space: `AppointmentApiIT.should_return_400_validation_failed_for_bad_booking_input` |
| TC-28 | Draft 7.2 | 409 `KUSS_NO_CAPACITY` leaves no key row | same-key retry runs fresh | retry succeeded once capacity returned | PASS | `IdempotencyIT.should_not_store_a_failed_booking_so_the_same_key_succeeds_once_capacity_returns` |
| TC-29 | Draft 11 #11 | Key older than 24 h | treated as absent | treated as absent | PASS | `IdempotencyIT.should_treat_a_key_older_than_24_hours_as_absent` |

### 4.3 Cancel

| ID | Source | Steps | Expected | Actual | Status | Existing coverage |
|---|---|---|---|---|---|---|
| TC-30 | Draft 7.3 | Cancel a confirmed future appointment, then book the freed slot | 200 `CANCELLED`; slot bookable again | as expected | PASS | `AppointmentApiIT.should_free_capacity_on_cancel_so_a_new_vehicle_can_book_the_slot`, BB |
| TC-31 | Draft 7.3 | Cancel an already cancelled appointment with a new key | 200 with the cancelled state | 200 | PASS | `AppointmentApiIT.should_replay_cancel_and_report_cancelled...`, BB |
| TC-32 | M-7 | Cancel an appointment whose start passed (row inserted by SQL: 10 min ago) | 409 `KUSS_APPOINTMENT_ALREADY_STARTED`, also on replay | 409, 409 | PASS | `AppointmentApiIT.should_return_409_already_started_when_cancelling_a_past_appointment`, BB |
| TC-33 | M-3 | Cancel an unknown id; invalid UUID; no key | 404 / 400 `KUSS_VALIDATION_FAILED` / 400 key missing | as expected | PASS | `AppointmentApiIT`, BB |
| TC-34 | Draft 7.3 | 10 parallel cancels of one appointment, different keys | all 200, one `CANCELLED` row | 10 x 200 | GAP-covered-now | `BookingEdgeCasesIT.should_cancel_once_and_answer_200_when_ten_requests_cancel_with_different_keys` |
| TC-35 | Draft 7.3 | 10 parallel cancels, same key | one 200, the rest 200 replay or 409 in flight | 5 x 200, 5 x 409 | PASS (known L-1) | BB |

### 4.4 Concurrency (M-11)

| ID | Source | Steps | Expected | Actual | Status | Existing coverage |
|---|---|---|---|---|---|---|
| TC-36 | M-11 | N=20 in-process vs K=3 oil pairs, and N=20 vs K=2 brake pairs | exactly K x 201, no overlap | as expected, 3 of 3 runs | PASS | `BookingConcurrencyIT` (4 tests), `AppointmentApiIT.should_return_exactly_3_created_and_7_no_capacity_for_10_parallel_http_requests` |
| TC-37 | M-11, BB | HTTP bursts N=24 vs K=3 (three days), K=2 (brake), K=1 (engine diagnostic) | exactly K x 201, N-K x 409 | `3/21`, `3/21`, `3/21`, `2/22`, `1/23` | PASS | BB with `xargs -P 24` |
| TC-38 | M-4 | 12 parallel requests for the same vehicle, different keys | 1 x 201, 11 x 409 `KUSS_VEHICLE_ALREADY_BOOKED` | `1/11` | PASS | `VehicleOverlapIT.should_accept_exactly_one_of_two_concurrent_requests_for_the_same_vehicle`, BB |
| TC-39 | Invariant | SQL overlap check over every confirmed row after all bursts | 0 overlapping bay, technician or vehicle pairs | 0 | PASS | BB (`psql` self-join on `tstzrange`) |
| TC-40 | Draft 9 | `scheduler.booking.gist_retries` after bursts | retries counted by type | bay 15, technician 6, deadlock 0 (BB); deadlocks appear in `BookingConcurrencyIT` | PASS | `MetricsIT`, BB |

### 4.5 Errors, input handling and overload

| ID | Source | Steps | Expected | Actual | Status | Existing coverage |
|---|---|---|---|---|---|---|
| TC-41 | Draft 8.1 | Invalid JSON, empty body, `[]`, `null`, wrong types | 400 `KUSS_VALIDATION_FAILED`, ProblemDetail with `code` and `requestId` | 400 | PASS | `SchedulerGlobalHandlerTest`, BB |
| TC-42 | Draft 8.1 | Blank, 41-char and missing fields; header `Idempotency-Key` invalid | 400 with a `detail` that names the offending field | 400, but the detail says `request: ...` (BUG-3) | FAIL at `99d0056`, fixed (BUG-3) | `SchedulerGlobalHandlerTest.should_return_400_validation_failed_naming_the_field_when_body_is_empty` called the handler directly. HTTP-level: `AppointmentValidationApiIT.should_name_each_invalid_field_once_in_the_400_detail` |
| TC-43 | Draft 8.1 | `PUT/GET/DELETE/PATCH` on wrong paths, `text/plain` and XML bodies, unknown path, `/api/v2` | 405 with `Allow`; 415; 404 `KUSS_RESOURCE_NOT_FOUND` | as expected; code is `KUSS_VALIDATION_FAILED` for 405 and 415 (see O-2) | PASS | `SchedulerGlobalHandlerTest.should_return_405_with_allow_header...`, `ErrorHandlingIT.should_return_404_resource_not_found_for_unknown_path`, BB |
| TC-44 | M-9 | Unhandled exception returns sanitized 500 with `requestId`, audited | 500 `KUSS_INTERNAL_ERROR`, no leak | as expected via test controller. A real `GET /error` returns `{"status":999,"error":"None"}` (BUG-5) | FAIL at `99d0056`, fixed (BUG-5) | `ErrorHandlingIT.should_return_sanitized_500_whose_request_id_matches_the_header`, `ErrorHandlingIT.should_return_404_problem_detail_for_a_direct_error_endpoint_request` |
| TC-45 | DD-2 | DB pool exhausted during `POST /appointments` (40 parallel against a 12 s `LOCK TABLE idempotency_key`) | 503 `KUSS_SERVICE_BUSY` with `Retry-After` | 20 x 503 with `Retry-After: 1` after about 3 s; 20 x 500 after about 5 s (BUG-2) | PASS for the pool case; FAIL at `99d0056` for the lock wait, fixed (BUG-2) | `AppointmentServiceImplTest.should_return_service_busy_when_no_database_connection_is_free`, `SchedulerGlobalHandlerTest.should_return_503_with_retry_after_when_service_is_busy`, `ServiceBusyIT.should_return_503_service_busy_when_booking_waits_on_a_locked_table` |
| TC-46 | DD-2 | Same overload on `POST /cancel` and on `GET availability` | 503 `KUSS_SERVICE_BUSY` | cancel: 40 x 500; availability: 20 x 500 after 3 s, the other 20 waited 8 s and returned 200 (BUG-1) | FAIL at `99d0056`, fixed (BUG-1) | `PoolExhaustionIT` (cancel and reads), `ServiceBusyIT` (cancel, availability, day list) |
| TC-47 | Plan RT-13 | Oversized `serviceTypeCode` (5 MB) | 400 | 400 | PASS | BB |
| TC-48 | Robustness | 60 MB valid booking with a junk field | rejected or bounded | accepted and booked in about 1 s (BUG-7) | FAIL (BUG-7, open, roadmap) | none |
| TC-49 | Robustness | Header of 20 KB; `%00` and `%ZZ` in the path | clean 400 | Tomcat HTML 400 page, not ProblemDetail (part of BUG-5) | FAIL at `99d0056`, fixed (BUG-5) | `ErrorHandlingIT.should_return_problem_detail_for_container_level_bad_requests` (`%00`, `%ZZ`); the 20 KB header case is not covered by a test |

### 4.6 Audit, metrics and operations

| ID | Source | Steps | Expected | Actual | Status | Existing coverage |
|---|---|---|---|---|---|---|
| TC-50 | Draft 7.4 | 30 `GET`s | 30 new `request_audit` rows | 119 -> 149 | PASS | `RequestAuditIT`, BB |
| TC-51 | Draft 7.4 | Error rows carry the `KUSS_*` code; 2xx null | codes 400, 404, 405, 409, 415, 422, 503 present with the right code | as expected, 500 from `/error` has a null code (BUG-5) | PASS (`/error` row: FAIL at `99d0056`, fixed with BUG-5) | `RequestAuditIT.should_audit_409_with_no_capacity_code_when_all_bays_are_busy`, BB |
| TC-52 | Dev 2026-10-31 | `GET /actuator`, `/actuator/health`, `/v3/api-docs`, `/swagger-ui.html` | none audited | `/actuator` (the index) audited (BUG-6); the rest not | FAIL at `99d0056`, fixed (BUG-6) | `RequestAuditIT.should_not_audit_actuator_requests` (now also `/actuator`) |
| TC-53 | Draft 7.4 | Payload whitelist | only the 4 booking fields, no PII, null on read | 4 fields; 60 MB body gives null | PASS | `RequestAuditIT.should_audit_booking_payload_with_only_whitelisted_fields`, BB |
| TC-54 | Draft 9 | `scheduler.booking.requests` result tags | include `KUSS_IDEMPOTENCY_KEY_MISSING`, `KUSS_VALIDATION_FAILED`, `KUSS_SERVICE_BUSY`; idempotency errors tag `unknown` | no key-missing series; boundary 400s uncounted (BUG-4); service-busy 20, idempotency mismatch `unknown` | FAIL at `99d0056`, fixed (BUG-4) | `MetricsIT.should_count_missing_idempotency_key_as_unknown_service_type`, `MetricsIT.should_count_boundary_validation_failures_as_unknown_service_type` |
| TC-55 | Draft 9 | Prometheus: HTTP, Hikari, availability timer | present, bounded `uri` tags | present; unknown paths tag `UNKNOWN` | PASS | `MetricsIT.should_expose_scheduler_meters_on_prometheus`, BB |
| TC-56 | Draft 9 | `health/liveness`, `health/readiness` | 200 `UP` | 200 | PASS | `ErrorHandlingIT.should_echo_request_id_header_on_actuator_health`, BB |
| TC-57 | Non-goal | `/actuator/env`, `/actuator/beans` | not exposed | 404 | PASS | BB |
| TC-58 | Draft 7.4 | `X-Request-Id` on every response and equal to `requestId` in the body | yes | yes | PASS | `ErrorHandlingIT`, BB |

## 5. Bug list

Booking correctness and idempotency held under test. The bugs below are in overload handling, error-shape consistency, observability and input limits. Each heading keeps the severity and the description from the retest at `99d0056`; the **Status** line is the state at `0cc274f`. The `Suspected` line of a fixed bug describes the old code.

### BUG-1 (Medium): overload outside `POST /appointments` returns 500 instead of 503 `KUSS_SERVICE_BUSY`

- **Status: FIXED** in KUSS-04 commit `1a50b25` (pre-squash `48fa8f8`, on `backup/KUSS-04-pre-squash`).
- **Code at `0cc274f`:** `SchedulerGlobalHandler.java:53-62` maps `CannotCreateTransactionException` and `QueryTimeoutException` to 503 `KUSS_SERVICE_BUSY` with `Retry-After`. `cancel` has a 5 s timeout at `AppointmentServiceImpl.java:115`, and the reads too (`AppointmentServiceImpl.java:147,160`, `AvailabilityServiceImpl.java:53`, `CatalogServiceImpl.java:46,52,67,75`).
- **Tests (green in the 2026-11-01 verify):** `PoolExhaustionIT.should_return_503_service_busy_on_cancel_when_no_connection_is_free`, `PoolExhaustionIT.should_return_503_service_busy_on_reads_when_no_connection_is_free`, `ServiceBusyIT.should_return_503_service_busy_when_cancel_waits_on_a_locked_table`, `ServiceBusyIT.should_return_503_service_busy_when_availability_waits_on_a_locked_table`, `ServiceBusyIT.should_return_503_service_busy_when_day_list_waits_on_a_locked_table`.

- **Endpoints:** `POST /api/v1/appointments/{id}/cancel`, all reads (`GET availability`, `GET appointments`, `GET /dealerships`, and so on).
- **Decision violated:** DD-2 and draft section 8.1, `KUSS_SERVICE_BUSY`: "the system is overloaded (DB pool exhausted...)" returns 503 with `Retry-After`, "instead of 500".
- **Repro:**

  ```sh
  podman compose exec -T db psql -U kuss -d kuss -c "begin; lock table idempotency_key in exclusive mode; select pg_sleep(12); commit;" &
  seq 1 40 | xargs -P 40 -I{} curl -s -D - -o /dev/null -X POST \
    localhost:8080/api/v1/appointments/<confirmed-appointment-id>/cancel -H "Idempotency-Key: k-{}"
  ```

  For reads, hold `lock table appointment in access exclusive mode` and fire 40 parallel `GET /api/v1/dealerships/{id}/availability?date=...&serviceType=OIL_CHANGE`.
- **Expected:** a pool timeout returns 503 `KUSS_SERVICE_BUSY` with `Retry-After: 1`.
- **Actual:** cancel: 40 x `500 KUSS_INTERNAL_ERROR` (20 from the 3 s pool timeout, 20 from the 5 s statement timeout). Availability: 20 x 500 after 3 s, and the other 20 requests waited 8 s for the lock and returned 200. The same 500s carry an ERROR stack trace in the log (`QueryTimeoutException`, pool timeout).
- **Suspected:** only `book` maps `CannotCreateTransactionException`, at `AppointmentServiceImpl.java:198-200`. `cancel` (`:346-364`) and the read services have no mapping, so `SchedulerGlobalHandler.handleUnexpected` (`SchedulerGlobalHandler.java:101-115`) turns it into 500.
- **Note:** the fix list F-3 only covered the booking path.

### BUG-2 (Low): a database lock stall during booking returns 500, and read transactions have no timeout

- **Status: FIXED** in KUSS-04 commit `1a50b25` (pre-squash `48fa8f8`).
- **Code at `0cc274f`:** a statement timeout during booking is mapped to `serviceBusy()` at `BookingConflictHelper.java:39-42`; the booking transaction has a 5 s timeout (`AppointmentServiceImpl.java:83`) and a missing connection is mapped at `:95-97`. Read transactions now carry the same 5 s timeout (see BUG-1).
- **Test:** `ServiceBusyIT.should_return_503_service_busy_when_booking_waits_on_a_locked_table` asserts the 503 and that a retry with the same key then books (201), so the claim was rolled back.

- **Endpoint:** `POST /api/v1/appointments`, and `GET` endpoints.
- **Decision violated (intent):** DD-2, "overload is temporary and retryable", and the 10 s deadline in DD-1.
- **Repro:** the BUG-1 lock command on `idempotency_key`, then 40 parallel `POST /appointments`.
- **Expected:** a request that cannot finish because the database is stalled returns 503 `KUSS_SERVICE_BUSY` (the claim rolled back, so a same-key retry is safe).
- **Actual:** 20 x `500 KUSS_INTERNAL_ERROR` after about 5 s (`QueryTimeoutException`, `canceling statement due to user request` on the `idempotency_key` insert), logged at ERROR with a stack trace. Read-only transactions have no timeout: an availability `GET` waited 8 s for the table lock.
- **Suspected:** `AppointmentServiceImpl.java:195-201` catches `DataAccessException` only for retryable SQLSTATEs (`:274-276` rethrows the rest), and `:60-61` sets the 5 s timeout on booking only. The cross-review (Q3) recorded that a timed-out wait returns 500; DD-2 does not list it.

### BUG-3 (Low): `400 KUSS_VALIDATION_FAILED` detail for `POST /appointments` names the parameter `request`, not the field, and repeats

- **Status: FIXED** in KUSS-04 commit `1a50b25` (pre-squash `68de448`).
- **Code at `0cc274f`:** `SchedulerGlobalHandler.handleMethodValidation` (`:86-95`) and `validationMessages` (`:97-105`) report `getFieldErrors()` as `field: message` for body errors, and `.distinct()` removes repeats.
- **Test:** `AppointmentValidationApiIT.should_name_each_invalid_field_once_in_the_400_detail` (empty body names the four fields and not `request:`; a 41-char service type starts with `serviceTypeCode: `; a bad key starts with `idempotencyKey: `; no duplicates).

- **Endpoint:** `POST /api/v1/appointments`.
- **Decision violated:** draft section 8.1 (machine-readable validation errors) and the existing test `SchedulerGlobalHandlerTest.should_return_400_validation_failed_naming_the_field_when_body_is_empty`.
- **Repro:**

  ```sh
  curl -s -X POST localhost:8080/api/v1/appointments -H 'Idempotency-Key: k1' \
    -H 'Content-Type: application/json' -d '{}'
  ```

- **Expected:** `dealershipId: must not be null, vehicleId: must not be null, ...`
- **Actual:** `"detail":"request: must not be null, request: must not be blank, request: must not be null, request: must not be null"`. A 41-char service type gives `request: size must be between 0 and 40`.
- **Suspected:** `@Pattern` on the header parameter (`AppointmentController.java:34-37`) turns on method validation, so the body is reported by `HandlerMethodValidationException` and `SchedulerGlobalHandler.handleMethodValidation` (`:67-80`) uses `getParameterName()`, which is `request`. The field-name path (`handleBodyValidation`, `:57-65`) is never reached for this endpoint.

### BUG-4 (Low): `scheduler.booking.requests` never counts `KUSS_IDEMPOTENCY_KEY_MISSING` or any 400 raised before the service

- **Status: FIXED** in KUSS-04 commit `1a50b25` (pre-squash `1ad00dd`).
- **Code at `0cc274f`:** `SchedulerGlobalHandler.reject` (`:139-153`) increments `scheduler.booking.requests` for a `POST /api/v1/appointments` with the result tag set to the error code and the service tag `unknown`. The missing-header (`:64-74`), body-validation (`:76-84`), method-validation (`:86-95`) and malformed-body (`:117-121`) handlers all go through it.
- **Tests:** `MetricsIT.should_count_missing_idempotency_key_as_unknown_service_type`, `MetricsIT.should_count_boundary_validation_failures_as_unknown_service_type` (3 boundary 400s add 3 to the counter).

- **Endpoint:** `POST /api/v1/appointments`.
- **Decision violated:** draft section 9, result tag set `confirmed|KUSS_NO_CAPACITY|KUSS_VEHICLE_ALREADY_BOOKED|KUSS_VALIDATION_FAILED|KUSS_IDEMPOTENCY_KEY_MISSING|KUSS_INTERNAL_ERROR` (+ `KUSS_SERVICE_BUSY`).
- **Repro:** send 4 requests without `Idempotency-Key` and 5 with bad bodies, then `curl -s localhost:8080/actuator/prometheus | grep booking_requests`.
- **Expected:** a `result="KUSS_IDEMPOTENCY_KEY_MISSING"` series and a `KUSS_VALIDATION_FAILED` count that matches the 400s.
- **Actual:** no `KEY_MISSING` series exists. `KUSS_VALIDATION_FAILED` was 11 against 43 audited 400 validation rows, because only failures raised inside `book()` are counted.
- **Suspected:** `BOOKING_RESULTS` has no `KEY_MISSING` (`AppointmentServiceImpl.java:71-81`) and counting is done in `book()` (`:146-158`), which is not reached when the header, body binding or method validation fails.

### BUG-5 (Low): `GET /error` and container-level failures return non-ProblemDetail responses

- **Status: FIXED** in KUSS-04 commit `1a50b25` (pre-squash `b876d78`).
- **Code at `0cc274f`:** `SchedulerErrorController.java:21-39` replaces the default `/error` page and rethrows the original status into `SchedulerGlobalHandler`. `ErrorReportConfiguration.java:20-35` and `ProblemDetailErrorReportValve.java:21` replace Tomcat's HTML error report with a ProblemDetail.
- **Tests:** `ErrorHandlingIT.should_return_404_problem_detail_for_a_direct_error_endpoint_request` (`GET /error` is a 404 `application/problem+json` with `code` and `requestId`), `ErrorHandlingIT.should_return_problem_detail_for_container_level_bad_requests` (`%00` and `%ZZ` give a 400 ProblemDetail, no HTML). The oversized-header case from the repro is not covered by a test.

- **Endpoint:** `GET /error`, requests rejected by Tomcat (header over 8 KB, `%00` or `%ZZ` in the path).
- **Decision violated:** draft section 8.1 (every non-2xx is `application/problem+json` with `code` and `requestId`) and section 7.4 (error code set on non-2xx).
- **Repro:** `curl -si localhost:8080/error` and `curl -si "localhost:8080/api/v1/dealerships/%00"`.
- **Expected:** a ProblemDetail (404 `KUSS_RESOURCE_NOT_FOUND`, or 400 `KUSS_VALIDATION_FAILED`).
- **Actual:** `/error` returns `500 {"timestamp":"...","status":999,"error":"None"}` with no `code` or `requestId`, and the audit row is `500` with a null `error_code`. Tomcat-level failures return an HTML "HTTP Status 400" page.
- **Suspected:** Spring Boot's default `BasicErrorController` is exposed and `/error` is not excluded or mapped; there is no custom `ErrorController` and no `server.error` configuration (`application.yml`).

### BUG-6 (Low): the actuator index `GET /actuator` is audited

- **Status: FIXED** in KUSS-04 commit `1a50b25` (pre-squash `f6235f4`).
- **Code at `0cc274f`:** `RequestAuditFilter.java:38-39` lists the prefix `"/actuator"` without the trailing slash, and `:91` skips any servlet path that starts with a listed prefix.
- **Test:** `RequestAuditIT.should_not_audit_actuator_requests` requests `/actuator` and `/actuator/health` and expects no audit row for either.

- **Endpoint:** `GET /actuator`.
- **Decision violated:** developer decision 2026-10-31, audit scope "all paths except actuator and API docs".
- **Repro:** `curl -s localhost:8080/actuator`, then `select http_method, path from request_audit where path like '/actuator%'`.
- **Expected:** no row.
- **Actual:** a row `GET /actuator 200`. Paths under `/actuator/` are skipped.
- **Suspected:** `RequestAuditFilter.java:36-37` lists the prefix `"/actuator/"` (with the trailing slash), which does not match `/actuator`.

### BUG-7 (Low): no request-body size limit

- **Status: OPEN (roadmap).** Not part of the KUSS-04 fixes. Re-checked at `0cc274f`: `src/main` sets no body-size cap (no Tomcat, Spring or Jackson limit), so the 60 MB body would still be accepted. No test covers it.

- **Endpoint:** `POST /api/v1/appointments` (any JSON endpoint).
- **Decision:** none violated directly. Developer decision 2026-10-31 (no rate limiting) does not cover body size, and the PDF asks for reliability.
- **Repro:** send a valid booking body with an extra field `"junk":"B...B"` of 60 MB (`--data-binary @file`, `-H 'Expect:'`).
- **Expected:** 413, or a bounded read.
- **Actual:** `201` in about 1 s. The whole body is read and parsed, and the booking is created.
- **Suspected:** no cap is configured in `application.yml` (Jackson skips unknown properties without enforcing its string limit; Tomcat only limits form posts).

## 6. Observations (not bugs)

- **O-1** The completed-duplicate case returns 409 `KUSS_IDEMPOTENCY_IN_FLIGHT` (known L-1); 12 parallel same-key bookings gave 1 x 201 and 11 x 409, and 10 same-key cancels gave 5 x 200 and 5 x 409.
- **O-2** `405` and `415` use code `KUSS_VALIDATION_FAILED` with their own status (405 carries `Allow`). The catalog lists `KUSS_VALIDATION_FAILED` as 400 only. This is asserted by `SchedulerGlobalHandlerTest`, so it looks intentional; the catalog should say so.
- **O-3** Unknown JSON fields (`customerId`, `bogus`) are ignored and are not part of the idempotency hash: the same key with an extra field replays. This matches "customer derived from vehicle".
- **O-4** An empty `Idempotency-Key:` header is reported as `KUSS_IDEMPOTENCY_KEY_MISSING`, while a bad character set is `KUSS_VALIDATION_FAILED`.
- **O-5** Availability omits slots with capacity 0 instead of listing them with 0. Availability for today after closing returns 200 with an empty list. Slot `startTime` carries the dealership offset (`Z` in London winter, `-05:00` in New York).
- **O-6** Service type codes are case-sensitive: `oil_change` is 404.
- **O-7** Under pool exhaustion the audit write competes for the same pool: 58 of the expected 60 `500` rows were audited. The failures are counted and swallowed, as designed.
- **O-8** The audit payload stores the four whitelisted fields as sent (up to the 4096-byte cache), so a free-text `serviceTypeCode` is stored unvalidated. The whitelist limits fields, not values.
- **O-9** `GET /customers` lists all customers and vehicle ids (accepted by decision 9; no auth in V1).
- **O-10** `GET /swagger-ui.html` redirects (302) and `/v3/api-docs` returns an OpenAPI 3.1 document; both are unaudited as designed.
- **O-11** Same-key replay does not change `createdAt`; a replayed `201` returns the stored body, so `createdAt` is the first request's time.
- **O-12** Prometheus `http_server_requests` `uri` tags stay bounded (`UNKNOWN`, `/**`, templated paths).
- **O-13** No `deadlock` (`40P01`) retry was counted in the HTTP bursts (bay 15, technician 6, deadlock 0); `40P01` only showed up in the in-process `BookingConcurrencyIT` runs.
- **O-14** DD-1 and DD-2 follow-ups are in place: 10 s deadline class `BookingDeadline`, `KUSS_SERVICE_BUSY` 503 with `Retry-After: 1`, F-1 (`unknown` tag for idempotency errors), F-5 (audit task wrapped), F-6 (cancel timeout) and F-7 (read date bounds). They behaved as specified in the checks above, apart from BUG-1 and BUG-2, which KUSS-04 has since fixed.

## 7. Tests added in this story

`src/test/java/com/keyloop/scheduler/appointment/controller/BookingEdgeCasesIT.java` (commit `ef0232a`, 7 tests, Testcontainers Postgres, relative dates, no mocks):

| Test | Covers |
|---|---|
| `should_book_the_last_slots_that_end_exactly_at_closing_time` | TC-09 |
| `should_book_back_to_back_services_for_the_same_vehicle` | TC-08 |
| `should_accept_an_idempotency_key_of_128_characters` | TC-27 |
| `should_reject_a_cancel_key_reused_for_another_appointment_as_422` | TC-24 |
| `should_reject_a_booking_key_reused_to_cancel_as_422` | TC-23 |
| `should_cancel_once_and_answer_200_when_ten_requests_cancel_with_different_keys` | TC-34 |
| `should_create_one_appointment_when_parallel_requests_share_an_idempotency_key` | TC-26 |

No test was written in this story for BUG-1 to BUG-7: at the time those behaviors were wrong and no failing or disabled test was committed. KUSS-04 added the regression tests for BUG-1 to BUG-6 together with the fixes (named per bug in section 5). BUG-7 has no test; when it is fixed, the repro in section 5 is the regression test to add.
