# KUSS-04 review: decision alignment and code findings

- Scope: `git log ea27bb9..HEAD` on `feature/KUSS-04-unified-service-scheduler` (16 commits, `6b921cc`..`5e23630`), plus the untracked docs only where a decision covers them.
- Reviewers: Claude Opus 5.5 (this report), with a code-reviewer subagent pass. Cross-review with Antigravity: `docs/agent-discussion/claude-to-antigravity.md` C-023.
- Mode: review only, no code changed.
- Date: 2026-10-31.

## Verification evidence

| Check | Command | Result |
|---|---|---|
| Full build | `./mvnw -B verify` (Podman env from `kuss-env.sh`) | BUILD SUCCESS, 30.3 s. Surefire 35 tests, Failsafe 72 tests, 0 failures, 0 errors, 0 skipped. Spotless check passed. Checkstyle: "You have 0 Checkstyle violations." |
| Concurrency gate, run 1 | `./mvnw -B verify -Dit.test=BookingConcurrencyIT -Dtest=NONE ...` | 4/4 pass, 6.54 s, 2× 40P01, 33× 23P01 |
| Concurrency gate, run 2 | same | 4/4 pass, 17.64 s, 82× 40P01, 25× 23P01 |
| Concurrency gate, run 3 | same | 4/4 pass, 9.89 s, 35× 40P01, 27× 23P01 |
| Method length, main | Checkstyle 14.3.0 `MethodLength max=20 countEmpty=true` over `src/main/java` | 0 violations |
| Method length, test (not gated) | same config over `src/test/java`, run with the Checkstyle CLI | 10 methods over 20 lines (listed in C-6 below). Tests are excluded by rule (`docs/code-standards.md:245`). |
| No inline comments | `grep -rnE '^\s*//\|/\*[^*]\|[^:"]//\s' src/main/java src/test/java` | 0 hits |
| No `@Data` / `@EqualsAndHashCode` / `@ToString` | grep over `src` | 0 hits |
| JPA only in main | `grep -rnE 'JdbcClient\|JdbcTemplate' src/main` | 0 hits |
| No bay/tech locks | `grep -rn 'PESSIMISTIC\|FOR UPDATE' src/main` | only `AppointmentRepository.java:92` (cancel `findForUpdate`) |
| Not pushed | `git branch -r --contains HEAD` | empty |

The 40P01 and 23P01 counts come from the Hibernate `HHH000247 ... SQLState:` WARN lines in each run's log.

## Deliverable 1: decision alignment table

Status values: **ALIGNED**, **PARTIAL** (implemented, with a gap noted), **DEVIATION** (contradicts the decision), **N-A** (process decision, or nothing in the code to check).

### A. Draft §12 and developer decisions (`docs/brainstorm/first-draft-high-level.md` §12)

| # | Decision | Source | Evidence | Status / note |
|---|---|---|---|---|
| A-1 | Approach A: three partial `EXCLUDE USING gist` constraints on bay, tech and vehicle, using `[)` ranges and `WHERE status='CONFIRMED'` | §12 Concurrency (dev 2026-10-30) | `V1__create_schema.sql:133-171`; `SchemaConstraintsIT.should_reject_overlapping_{bay,technician,vehicle}_booking`, `should_allow_back_to_back_bookings_on_same_bay_technician_and_vehicle` | ALIGNED |
| A-2 | Re-query and retry until no free pair is left, on 23P01 or 40P01, with one whole transaction per attempt | §12; C-017 #1 | `AppointmentServiceImpl.java:69` (`RETRYABLE_STATES`), `:191-205` (loop, `TransactionTemplate`), `:263-276` (`handleConflict`); `BookingConcurrencyIT` 3/3 runs green | ALIGNED |
| A-3 | A vehicle conflict returns 409 immediately, with no retry | §12 #4; skill checklist | `:232-234` (precheck), `:266-268` (23P01 on `excl_appointment_vehicle_overlap`); `VehicleOverlapIT.should_reject_an_overlapping_service_for_the_same_vehicle_without_retrying`, `should_accept_exactly_one_of_two_concurrent_requests_for_the_same_vehicle` | ALIGNED |
| A-4 | No bay or technician locks before insert (lock revert) | Execution and review, lock-revert decision | `ab4300c` drops the lock code in `AppointmentWriteRepository` and both entity repositories; the grep finds only the cancel lock | ALIGNED |
| A-5 | 40P01 backoff (`ab4300c`): a randomized wait that starts at `deadlock_timeout` and doubles up to 3.2-6.4 s; no connection held while waiting | Execution and review | `:285-294`: ceiling `100ms << min(attempt,6)`, sleep in `[ceiling/2, ceiling]`, so attempt 1 waits 100-200 ms and attempt 6 or later waits 3.2-6.4 s. The sleep runs in the `catch` after `transactionTemplate.execute` has rolled back and released the connection. `book()` is not `@Transactional` and `open-in-view: false` (`application.yml:13`). | PARTIAL: the exponent counts *all* attempts, not deadlocks, so a first deadlock after k bay/tech conflicts starts at 2^k × 100 ms rather than at `deadlock_timeout`. There is also no overall deadline, and the last attempt still sleeps (finding **M-1**). The cap, the randomization and holding no connection all match. |
| A-6 | Random choice among free bays | §12 Approach A | `CandidateSelectionUtils.java:46`; `CandidateSelectionTest.should_pick_every_free_bay_across_many_picks` | ALIGNED |
| A-7 | Technician with the fewest confirmed minutes that day, random among ties | §12 Technician heuristic | `CandidateSelectionUtils.java:17-48`; `AppointmentServiceImpl.java:255-260` (dealership-local day, `:241`); `CandidateSelectionTest.should_pick_the_technician_with_fewest_booked_minutes`, `should_pick_only_among_tied_least_loaded_technicians` | ALIGNED |
| A-8 | `Idempotency-Key` required on book AND cancel | §12 Idempotency (dev 2026-10-30) | `AppointmentController.java:34,49` (required `@RequestHeader`); `SchedulerGlobalHandler.java:34-43`; `AppointmentApiIT.should_return_400_idempotency_key_missing_on_book_and_cancel` | ALIGNED |
| A-9 | Key row claimed first, in the same transaction as the booking or cancel | §12; C-017 #2 | `IdempotencyServiceImpl.java:43-53` (`Propagation.MANDATORY`), `:64-79`; `AppointmentServiceImpl.java:207-221` (book), `:342-359` (cancel) | ALIGNED |
| A-10 | Replay the stored response; 422 on payload mismatch; 409 while in flight | §12 #6, #8, #10 | `IdempotencyServiceImpl.java:73-95`; `IdempotencyIT.should_replay_the_stored_result_for_the_same_key_and_request`, `should_reject_the_same_key_with_a_different_payload_as_422`, `should_report_in_flight_when_the_blocking_claim_commits`, `should_book_when_the_blocking_claim_is_rolled_back`; `AppointmentApiIT.should_replay_cancel_and_report_cancelled_for_a_new_key_on_a_cancelled_appointment` | ALIGNED. See also L-1 and L-2. |
| A-11 | 24 h key cleanup | §12 | `MaintenanceServiceImpl.java:29,70-72`; `PartitionMaintenanceIT.should_delete_idempotency_keys_older_than_twenty_four_hours_and_keep_newer` | ALIGNED. The cleanup runs daily, so a key actually lives 24-48 h, and nothing checks expiry on read (L-2). |
| A-12 | Hash over canonical fields, whitespace- and offset-insensitive | C-017 #10 | `AppointmentServiceImpl.java:405-414` (`toInstant()`), `:396-398` (cancel); `IdempotencyIT.should_replay_when_the_same_instant_is_sent_with_another_offset`; `AppointmentApiIT.should_replay_book_with_reformatted_json_as_the_same_appointment` | ALIGNED |
| A-13 | `request_audit` written asynchronously on a bounded executor (2/2/1000) and dispatched after the business transaction | §12 Request audit; C-017 #4 | `AuditConfiguration.java:10-23`; `RequestAuditFilter.java:73-78` (submitted in `finally`, after the chain has committed), `:87-93` | ALIGNED |
| A-14 | Audit write in its own `REQUIRES_NEW` transaction; failures counted, logged and swallowed | §12; skill checklist | `RequestAuditServiceImpl.java:40-55`; `RequestAuditIT.should_count_failed_write_without_throwing`, `should_audit_500_with_internal_error_code_when_handler_throws` | PARTIAL: a failure to get a connection or to commit happens in the proxy, outside the try/catch. It escapes into the `audit-` thread, uncounted and without the requestId log line (finding **M-2**). Isolation from the client and the business transaction holds. |
| A-15 | Daily partitions, D through D+7, created at startup and then daily, `IF NOT EXISTS`, failures logged | §12; C-017 #3; A-023 #1 | `MaintenanceServiceImpl.java:56-107`, `SmartLifecycle` phase `Integer.MIN_VALUE` `:91-94`, `runStep` `:130-136`; `PartitionMaintenanceIT.should_create_partitions_for_today_through_seven_days_ahead_at_startup`, `should_keep_default_partition_empty_for_today_after_startup`, `should_run_daily_twice_without_error_and_keep_the_same_partitions` | ALIGNED |
| A-16 | BRIN index on `occurred_at` | §12 | `V1__create_schema.sql:216-219` | ALIGNED |
| A-17 | 14-day retention | §12 | `MaintenanceServiceImpl.java:28,65-69,109-115`; `PartitionMaintenanceIT.should_drop_partitions_older_than_fourteen_days_and_keep_newer`, `should_delete_default_rows_older_than_fourteen_days_and_keep_newer` | ALIGNED |
| A-18 | Audit scope: every path except actuator and API docs, matched on the servlet path | §12 (dev 2026-10-31, RT #10) | `RequestAuditFilter.java:35-36,69,83-85`; `RequestAuditIT.should_not_audit_actuator_requests`, `should_audit_404_with_resource_not_found_code`, `should_audit_availability_read_with_no_error_code_and_null_payload` | ALIGNED |
| A-19 | KUSS code copied to `request_audit.error_code` | §12 Error handling | `SchedulerGlobalHandler.java:146`; `RequestAuditFilter.java:107`; `RequestAuditIT.should_audit_409_with_no_capacity_code_when_all_bays_are_busy` | ALIGNED |
| A-20 | Audit payload sanitized (four-field whitelist, no PII), path and method truncated | RT #10a; phase 07 | `RequestAuditFilter.java:39-43,113-139`; `RequestAuditIT.should_audit_booking_payload_with_only_whitelisted_fields`, `should_truncate_audited_path_to_column_length`, `should_audit_malformed_booking_as_400_with_null_payload` | ALIGNED |
| A-21 | One `@RestControllerAdvice` that returns RFC 9457 `ProblemDetail` with `code` (KUSS_*) and `requestId` | §12 Error handling (dev 2026-10-30) | `SchedulerGlobalHandler.java:25-148` (only advice in `src/main`), `:142-147`; `SchedulerGlobalHandlerTest.should_return_problem_with_status_and_code_for_each_scheduler_error_code` | ALIGNED |
| A-22 | Explicit enum catalog of KUSS codes | §12 | `ErrorCode.java:5-14` (9 codes, statuses as in §12 #1-#9) | ALIGNED |
| A-23 | `requestId` correlated across MDC, `X-Request-Id` and the audit row | §12 | `RequestAuditFilter.java:65-68,103`; `ErrorHandlingIT.should_return_sanitized_500_whose_request_id_matches_the_header`, `should_echo_request_id_header_on_actuator_health` | ALIGNED |
| A-24 | No leaks: a sanitized 500, fixed 4xx details, no SQL, Java types or stack traces | §12 #9; RT #9 | `SchedulerGlobalHandler.java:86-129`; `SchedulerGlobalHandlerTest.should_return_500_internal_error_without_leaking_exception_text`, `should_keep_java_and_framework_types_out_of_4xx_details` | ALIGNED |
| A-25 | Minimal POST body `{dealershipId, vehicleId, serviceTypeCode, desiredStartTime}`; customer derived from the vehicle | §12 C-011 #3 | `BookAppointmentDto.java:11-15`; `AppointmentServiceImpl.java:238` | ALIGNED |
| A-26 | Extra read endpoints plus cancel | §12 C-011 #4 | `CatalogController.java:23-38` (`/dealerships`, `/dealerships/{id}`, `/service-types`, `/customers`); `AvailabilityController.java:17`; `AppointmentController.java:41-60` | ALIGNED |
| A-27 | No auth; request signing roadmap only; no Redis, RabbitMQ or WebSocket in code or compose | §12; development-rules | `grep -rniE 'redis\|rabbit\|amqp\|websocket' pom.xml docker-compose.yml src/main`: 0 hits | ALIGNED |
| A-28 | 30-minute grid inside local opening hours; whole duration must fit before closing | §12 Core | `TimeSlotUtils.java:24-42`; `V1__create_schema.sql:15-45` (grid CHECKs); `TimeSlotUtilsTest.should_accept_a_start_only_when_it_is_a_grid_start_that_ends_by_close`, `should_shift_new_york_opening_when_daylight_saving_starts` | ALIGNED |
| A-29 | Every schema change via Flyway; `ddl-auto: validate`, `generate-ddl: false`, `clean-disabled: true` | §12 (dev 2026-10-31); Conv #7 | `application.yml:12-20`; only `V1`, `V2` in `db/migration` | ALIGNED |
| A-30 | The only DDL outside Flyway is the partition create/drop job; names come only from the date formatter or the regex | §12 (dev 2026-10-31); phase 07 | `MaintenanceServiceImpl.java:30-35,96-124` | ALIGNED. No identifier injection is possible. |
| A-31 | Stack: Java 21 with virtual threads, Spring Boot 4.0.x, PostgreSQL, Docker | §12 Core | `pom.xml:7` (4.0.9), `:17` (21); `application.yml:5-7` | ALIGNED |

### B. Mandatory test scenarios (§12 list 1-11)

| # | Scenario | Evidence (tests) | Status |
|---|---|---|---|
| S-1 | 400 `KUSS_VALIDATION_FAILED` for off-grid, past, outside-hours, beyond-horizon or malformed input | `AppointmentApiIT.should_return_400_validation_failed_for_bad_booking_input` (off-grid, yesterday, +91 days, …); `TimeSlotUtilsTest` | ALIGNED |
| S-2 | 400 `KUSS_IDEMPOTENCY_KEY_MISSING` | `AppointmentApiIT.should_return_400_idempotency_key_missing_on_book_and_cancel`; `SchedulerGlobalHandlerTest.should_return_400_idempotency_key_missing_when_header_absent` | ALIGNED |
| S-3 | 404 `KUSS_RESOURCE_NOT_FOUND` | `AppointmentApiIT.should_return_404_for_unknown_dealership_vehicle_service_type_and_appointment`; `AvailabilityApiIT` 404 cases | ALIGNED |
| S-4 | 409 `KUSS_VEHICLE_ALREADY_BOOKED` | `VehicleOverlapIT` (4 tests); `AppointmentApiIT.should_return_409_vehicle_already_booked_for_an_overlapping_service` | ALIGNED |
| S-5 | 409 `KUSS_NO_CAPACITY` | `AppointmentApiIT.should_return_409_no_capacity_when_the_fourth_oil_change_hits_a_full_slot`; `BookingConcurrencyIT` | ALIGNED |
| S-6 | 409 `KUSS_IDEMPOTENCY_IN_FLIGHT` | `IdempotencyIT.should_report_in_flight_when_the_blocking_claim_commits` | ALIGNED |
| S-7 | 409 `KUSS_APPOINTMENT_ALREADY_STARTED` | `AppointmentApiIT.should_return_409_already_started_when_cancelling_a_past_appointment` | ALIGNED |
| S-8 | 422 `KUSS_IDEMPOTENCY_KEY_MISMATCH` | `IdempotencyIT.should_reject_the_same_key_with_a_different_payload_as_422`; `AppointmentApiIT.should_return_422_when_the_same_key_carries_another_payload` | ALIGNED |
| S-9 | 500 sanitized and audited | `ErrorHandlingIT.should_return_sanitized_500_whose_request_id_matches_the_header`; `RequestAuditIT.should_audit_500_with_internal_error_code_when_handler_throws` | ALIGNED |
| S-10 | Replay produces exactly one row | `IdempotencyIT.should_replay_the_stored_result_for_the_same_key_and_request`; `AppointmentApiIT.should_replay_book_with_reformatted_json_as_the_same_appointment` | ALIGNED |
| S-11 | N parallel vs K free → exactly K × 201 | `BookingConcurrencyIT.should_book_exactly_3_bays_when_20_requests_race_for_oil_change_slots`, `should_book_exactly_2_technicians_when_20_requests_race_for_brake_repair_slots` (3/3 runs green); `AppointmentApiIT.should_return_exactly_3_created_and_7_no_capacity_for_10_parallel_http_requests` | ALIGNED |

### C. Conventions added after validation (plan.md table rows 1-11)

| # | Convention | Evidence | Status / note |
|---|---|---|---|
| C-1 | No inline comments; Javadoc only, required on core business methods; no `ponytail:` markers | grep: 0 inline comments, 0 `ponytail`. Javadoc on `AppointmentServiceImpl.book` `:138`, `cancel` `:336`, `backOffAfterDeadlock` `:278`; `IdempotencyServiceImpl.claim` `:37`; `AvailabilityServiceImpl.getAvailability` `:47`; `TimeSlotUtils.candidateStarts` `:19`, `isWithinHorizon` `:44`; `RequestAuditService` `:7,14`; `MaintenanceService` `:5` | ALIGNED |
| C-2 | Test methods `should_*` snake_case, no given/when/then | All `@Test` methods start with `should_` (grep finds no exception) | ALIGNED |
| C-3 | Entities use Lombok `@Getter @Setter @Builder` + protected no-args + private all-args; never `@Data` | All 9 `*Entity` classes carry all 5 annotations; 0 `@Data`/`@EqualsAndHashCode`/`@ToString` | ALIGNED |
| C-4 | Own domain types built with `X.builder()…build()` | Every DTO, entity and context is built with a builder. One exception: `RequestAuditMapper.java:12` builds `new RequestAuditKey(...)` (an `@Embeddable` record without `@Builder`) | PARTIAL, Low (L-9) |
| C-5 | MapStruct `*Mapper` interfaces; no mapping code in DTOs | `AppointmentMapper`, `AvailabilityMapper`, `CatalogMapper`, `RequestAuditMapper`; no `from(`/`toEntity(` in any `*Dto` | ALIGNED |
| C-6 | Spotless gate: google-java-format (main + test), unused imports, import order, sortPom, Flyway SQL, YAML, Markdown (excluding brainstorm, agent-discussion, plans); Checkstyle `MethodLength` 20 on main | `pom.xml` Spotless block (`googleJavaFormat`, `removeUnusedImports`, `importOrder`, `sortPom`, `dbeaver`, `jackson`, `flexmark` on `README.md` + `docs/*.md` with the three excludes), bound to `validate`; Checkstyle plugin bound to `validate`, `failOnViolation`. Verify: Spotless passed, 0 Checkstyle violations. Test sources (not gated) have 10 methods over 20 lines: `AppointmentApiIT:38` (33), `:93` (31), `:221` (25), `:262` (21); `BookingConcurrencyIT:78` (25), `:105` (22); `SchemaConstraintsIT:130` (24); `RequestAuditIT:116` (32), `:212` (21); `MetricsIT:43` (31) | ALIGNED (the rule is main-only, `code-standards.md:245`) |
| C-7 | Flyway only, never edit a committed migration | `V1__create_schema.sql` and `V2__insert_seed_data.sql`, each created once in `34ad3fd` | ALIGNED |
| C-8 | Migration names `V<n>__<verb_snake>.sql` | `V1__create_schema.sql`, `V2__insert_seed_data.sql` | ALIGNED |
| C-9 | Every message and every name shared across classes lives in `common.constants`; SQL stays in its repository | `ErrorMessageConstants`, `HeaderConstants`, `MetricConstants`, `RequestConstants`; all `SchedulerException` messages come from constants. Exception: the API path `/api/v1/appointments` is a literal in three classes (`AppointmentController.java:32`, `RequestAuditFilter.java:38`, `AppointmentServiceImpl.java:397,406`) | PARTIAL, Low (L-9) |
| C-10 | JPA only in `src/main`; `@Query` JPQL; native only where needed; cancel uses `@Lock PESSIMISTIC_WRITE`; partition DDL through the `EntityManager` | 0 `JdbcClient`; native only in `RequestAuditRepository.java:14-17` (a DEFAULT-partition delete, which JPQL cannot express) and `MaintenanceServiceImpl` DDL; `AppointmentRepository.java:92-94` | ALIGNED |
| C-11 | Packages by role: `<domain>/{controller,service,service/impl,repository,entity,dto,mapper,config,util}`; shared code in `common` | All 71 main classes sit in the subpackage that matches their suffix; `common/{constants,error,util,web}` | ALIGNED |

### D. Red Team Review #1-15

| # | Decision | Evidence | Status / note |
|---|---|---|---|
| RT-1 | `deadlock_timeout=100ms` in Testcontainers and compose; 40P01 retried | `TestcontainersConfiguration.java:18`; `docker-compose.yml:9-12`; `AppointmentServiceImpl.java:69` | ALIGNED. The backoff base is hard-coded to the same 100 ms (L-4). |
| RT-2 | `@ElementCollection` join column set explicitly | `TechnicianEntity.java:43-45`; `ddl-auto: validate` passes (context starts in every IT) | ALIGNED |
| RT-3 | A leaked lock-holder connection must not hang `TRUNCATE` | `IntegrationTestSupport.java:52-53` (`SET LOCAL lock_timeout='5s'`); `IdempotencyIT.java:104,119` (try-with-resources holder) | ALIGNED |
| RT-4 | Bounded prod pool, transaction and connection timeouts | `application.yml:38-41` (pool 20, `connection-timeout: 3000`); `AppointmentServiceImpl.java:60,122` (5 s per attempt); `:197-200` (pool exhausted → 500, WARN without stack trace, as phase 05 specifies) | ALIGNED. Cancel's `@Transactional` has no timeout (L-3). Pool exhaustion → 500 rather than 503 (L-5). |
| RT-5 | No duplicate service-level and HTTP tests | Test map: service-level N=20 in `BookingConcurrencyIT`, HTTP N=10/K=3 in `AppointmentApiIT` | ALIGNED |
| RT-6 | 29 h budget plus cut list | `plan.md` Phases and Cut list | N-A (a planning decision; recorded) |
| RT-7 | Early two-connection race test in phase 02 | `SchemaConstraintsIT.should_commit_only_one_of_two_concurrent_overlapping_bay_inserts`, added in `34ad3fd` (phase 02) | ALIGNED |
| RT-8 | A booked vehicle plus a full slot returns `VEHICLE_ALREADY_BOOKED`, not `NO_CAPACITY` | `AppointmentServiceImpl.java:232-234` runs before `pickCandidate`; `VehicleOverlapIT.should_report_vehicle_conflict_rather_than_no_capacity_when_the_slot_is_full` | ALIGNED |
| RT-9 | 4xx details carry no Java types; headers kept; `ConstraintViolationException` mapped | `SchedulerGlobalHandler.java:76-93,111-119` (`error.getHeaders()`); `SchedulerGlobalHandlerTest.should_return_405_with_allow_header_when_method_is_not_supported`, `should_return_400_validation_failed_when_constraint_violation_is_thrown`, `should_keep_java_and_framework_types_out_of_4xx_details` | ALIGNED |
| RT-10 | (a) audit scope and truncation; (b) security wording; (c) `CustomerDto` without VIN or plate | (a) `RequestAuditFilter.java:35-43,137-139`. (b) Fixed in the plan files (draft wording is routed to its owner). (c) `CustomerDto.java:8` `(id, fullName, vehicles)`, `VehicleDto.java:7` `(id, make, model, modelYear)`; `CatalogApiIT.should_list_customers_with_vehicles_exposing_only_public_fields` | ALIGNED |
| RT-11 | Test brittleness bundle: audit default row, lazy meters, DDL on a shared table, ns vs µs, New York time | DEFAULT-row test `SchemaConstraintsIT.should_route_request_audit_row_dated_2000_to_default_partition`; meters pre-registered (`5e23630`: `AppointmentServiceImpl.java:168-180`, `RequestAuditServiceImpl.java:35-36`, `AvailabilityServiceImpl.java:62-65`); `truncatedTo(MICROS)` at `AppointmentServiceImpl.java:213,351`, `IdempotencyServiceImpl.java:65`, `RequestAuditFilter.java:102`; `TimeSlotUtilsTest.should_shift_new_york_opening_when_daylight_saving_starts` | ALIGNED |
| RT-12 | PDF Req 3 links asserted | `BookingConcurrencyIT.should_link_customer_vehicle_technician_and_bay_on_the_stored_booking` | ALIGNED |
| RT-13 | (a) `candidateStarts.contains`; (b) 90-day horizon; (c) bounded `service_type` metric tag without a lookup; (d) trimmed framework tests and SQL kept in repositories | (a) `AppointmentServiceImpl.java:327-333`. (b) `TimeSlotUtils.java:15,48-50`, `AvailabilityServiceImpl.java:87-92`, `AvailabilityApiIT.should_return_400_when_date_is_beyond_the_90_day_horizon`, `TimeSlotUtilsTest.should_accept_a_date_up_to_90_days_ahead_of_today`. (c) `AppointmentServiceImpl.java:82-88,160-166`. (d) JPQL in repositories. | PARTIAL: (c) matches the phase-07 list word for word, but that list is wrong. `KUSS_IDEMPOTENCY_IN_FLIGHT` and `KUSS_IDEMPOTENCY_KEY_MISMATCH` are thrown in `claim()` (`:209`) *before* the service type is validated (`:227`), so the tag is unbounded user input (finding **H-1**). (a), (b) and (d) are ALIGNED. |
| RT-14 | Ops hardening: TCP healthcheck, restart, 127.0.0.1, non-root, pinned Checkstyle, key charset, no-rate-limit note, logged drops | `docker-compose.yml:15-21` (`pg_isready -h localhost`), `:29` (`restart: on-failure`), `:14` (`127.0.0.1:5432`); `Dockerfile:9,11` (uid 10001, `USER app`); `pom.xml:22,176-181` (Checkstyle 14.3.0); `AppointmentController.java:28` (`[A-Za-z0-9_-]{1,128}`); plan.md Out of scope (rate limiting); `RequestAuditServiceImpl.java:57-67` with `RequestAuditFilterTest.should_hand_audit_to_reject_when_executor_refuses_the_task` | ALIGNED. The app port `8080:8080` binds all interfaces, as phase 01 specifies (only the DB was required on 127.0.0.1). |
| RT-15 | Rejected: keep the DEFAULT partition and `response_status`; no management port, wrapper checksum or early-return cancel | `V1__create_schema.sql:189,212-214`; no management port configured | ALIGNED (the rejection is honored) |

### E. Validation Log 1-4

| # | Decision | Evidence | Status |
|---|---|---|---|
| V-1 | Podman runtime provisioned by the orchestrator; Testcontainers via `DOCKER_HOST`, socket override and Ryuk; fully-qualified images; `podman compose` | `TestcontainersConfiguration.java:16` (`docker.io/library/postgres:17`); `Dockerfile:1,8` and `docker-compose.yml:4` fully qualified; this review's 4 Maven runs passed under Podman with `kuss-env.sh` | ALIGNED |
| V-2 | Spring Boot 4.0.9, springdoc 3.2.0, Testcontainers 2 | `pom.xml:7,19`; `testcontainers-postgresql` and `testcontainers-junit-jupiter` (TC 2 artifact names) at `pom.xml:90-95` | ALIGNED |
| V-3 | Haiku implementer with `/implement --auto` and a per-phase `./mvnw verify` gate; one cross-review after phase 07 | Process decision; this report and C-023 are that cross-review | N-A |
| V-4 | Local `[KUSS-04]` commits, no push until the user approves, PR `Closes #4` | All 16 commits are `[KUSS-04] …`; `git branch -r --contains HEAD` is empty | ALIGNED |

### F. Execution and review decisions

| # | Decision | Evidence | Status |
|---|---|---|---|
| E-1 | Phase 07 split: 7a (audit + maintenance) built in parallel and cherry-picked; 7b (metrics, probes, logs) after phase 06 | `911fd54` / `d95cbcc`: author time 12:02-12:03, committer time 15:13 (cherry-picked after `ab4300c`/`15fda1c`); `9b7a7dd`, `5e23630` = 7b | ALIGNED |
| E-2 | Lock revert: Approach A (EXCLUDE + retry only) is the V1 decision; lock ordering moves to the roadmap | `ab4300c` (locks removed); `15fda1c` (roadmap line in the design draft); plan.md Out of scope | ALIGNED |
| E-3 | 40P01 randomized exponential backoff | See A-5 | PARTIAL (same row as A-5, not counted twice) |

### G. PDF Scenario A requirements

| # | Requirement | Evidence | Status |
|---|---|---|---|
| P-1 | A user requests an appointment for a specific vehicle, service type and dealership at a desired time | `POST /api/v1/appointments` `AppointmentController.java:32-39`; `BookAppointmentDto.java:11-15` | ALIGNED |
| P-2 | Before confirming, check that both a ServiceBay and a qualified Technician are free for the whole duration | `AppointmentRepository.java:30-64` (free bays; free technicians joined on `skillCodes`, `[start,end)` overlap); `AppointmentServiceImpl.java:245-261`; EXCLUDE backstop `V1:133-158`; `GET …/availability` with `AvailabilitySweepTest` (6 tests) | ALIGNED |
| P-3 | On success, create a persistent Appointment record linking customer, vehicle, technician and bay | `V1__create_schema.sql:102-116` (FKs to all four); `BookingConcurrencyIT.should_link_customer_vehicle_technician_and_bay_on_the_stored_booking` | ALIGNED |

### Totals

78 rows. E-3 is a duplicate of A-5 and is not counted, so the counts cover 77 rows:

- **ALIGNED: 70**
- **PARTIAL: 5** (A-5, A-14, RT-13, C-4, C-9)
- **DEVIATION: 0**
- **N-A: 2** (RT-6, V-3)

None of the code contradicts a recorded decision. The one High finding comes from a wrong list in the plan (RT-13c), which the code copies exactly.

## Deliverable 2: code review findings

No Critical findings. Double-booking is impossible: the three EXCLUDE constraints back it, and the concurrency gate was green in 3 of 3 runs. No connection is held during the 40P01 sleep. The idempotency claim commits or rolls back together with the booking.

### High

**H-1. User input becomes a Prometheus tag through the idempotency error paths (unbounded cardinality).** CONFIRMED.
- **Where:** `AppointmentServiceImpl.java:82-88`, `:160-166`, `:209`.
- **Defect:** `TRACKED_SERVICE_TYPE_RESULTS` contains `KUSS_IDEMPOTENCY_IN_FLIGHT` and `KUSS_IDEMPOTENCY_KEY_MISMATCH`. Both are thrown inside `idempotencyService.claim()` at `:209`, which runs before `findServiceType()` at `:227`, so `request.serviceTypeCode()` is tagged without being validated.
- **Failure scenario:** a client books once with key `k1` (vehicle ids are public through `GET /customers`). It then loops `POST /appointments` with `Idempotency-Key: k1` and a random `serviceTypeCode` of up to 40 characters. Every call returns 422, and every call creates a new `scheduler_booking_requests_total{service_type="<random>",result="KUSS_IDEMPOTENCY_KEY_MISMATCH"}` series. That is unbounded heap and scrape growth, with no auth needed.
- **Root cause:** phase-07 line 20 lists these two results as "reached after validation"; they are not.
- **Fix:** remove both codes from `TRACKED_SERVICE_TYPE_RESULTS`. Alternatively, tag with the code only when it is in the set pre-registered at `:169-180`, else `unknown`. Add a `MetricsIT` case: a mismatch with code `ZZZ` must tag `unknown`.

### Medium

**M-1. The 40P01 backoff has no overall deadline; its exponent counts all attempts; the last attempt still sleeps.** CONFIRMED.
- **Where:** `AppointmentServiceImpl.java:59,192-205,263-294`.
- **Worst case:** `MAX_ATTEMPTS = 100`, sleep ceiling `100ms << min(attempt,6)`.
  - Sleep alone is up to 0.2+0.4+0.8+1.6+3.2 + 95 × 6.4 ≈ 614 s (≈ 307 s at the lower jitter bound).
  - Each attempt can also wait up to the 5 s per-attempt timeout.
  - So one request can run for 10-18 minutes.
- **Connection held during the wait:** none (verified, see A-5).
- **Observed:** 82 deadlocks in one gate run, which took 17.6 s for 4 tests.
- **Failure scenario:** under real contention a gateway times out at 30 s. The client retries with the same key. Between attempts no claim is held, so the retry may win, while the original thread keeps retrying for minutes and drives the metrics up. A first deadlock after k 23P01 retries starts at 2^k × 100 ms rather than at `deadlock_timeout` (A-5 PARTIAL).
- **Fix:** count deadlocks separately for the exponent; add a wall-clock deadline (for example 10 s from the first attempt → `KUSS_INTERNAL_ERROR`); don't sleep after the final attempt.

**M-2. Audit failures raised by the transaction proxy escape the failure counter and the log line.** CONFIRMED.
- **Where:** `RequestAuditServiceImpl.java:40-55`, `RequestAuditFilter.java:87-93`.
- **Defect:** the try/catch is inside the `@Transactional(REQUIRES_NEW)` method. The proxy can throw outside it:
  - `CannotCreateTransactionException` (pool exhausted or DB down).
  - A failure at commit.
- **Failure scenario:** while the DB is down, every audit task throws in the `audit-` thread. `scheduler.audit.writes{status=failure}` stays flat, and no requestId WARN is logged (only the executor's default uncaught-exception trace). So the outage is invisible on the audit metric exactly when it matters. The client is never affected.
- **Fix:** in `submit`, wrap the task: `() -> { try { requestAuditService.write(audit); } catch (RuntimeException ex) { requestAuditService.reject(audit); } }`, or move the catch to a non-transactional caller.

### Low

- **L-1. A completed duplicate gets 409 IN_FLIGHT instead of a replay.** `IdempotencyServiceImpl.java:64-79`. CONFIRMED, by design.
  - B's INSERT blocks on A's uncommitted claim. When A commits, B gets 23505 → 409.
  - The plan specifies this (phase 05 §Key Insights), and `IdempotencyIT.should_report_in_flight_when_the_blocking_claim_commits` asserts it.
  - The `getResponseStatus()==null` branch at `:86` cannot be reached, because claim and complete always commit together. It is harmless dead code.
- **L-2. A key actually lives 24-48 h, and expiry is not checked on read.** `MaintenanceServiceImpl.java:57,72`, `IdempotencyServiceImpl.java:47`. CONFIRMED.
  - The purge runs daily at 00:05 UTC plus at startup.
  - A key 30 h old still replays (or gives 422).
  - Acceptable; document it, or treat `createdAt < now-24h` as absent.
- **L-3. Cancel has no transaction timeout.** `AppointmentServiceImpl.java:342`. CONFIRMED.
  - `findForUpdate` and the claim INSERT wait without a bound on a stalled holder.
  - Fix: `@Transactional(timeout = 5)`, the same as booking.
- **L-4. The backoff base of 100 ms is hard-coded to the `deadlock_timeout` that compose and Testcontainers set.** `AppointmentServiceImpl.java:61`. PLAUSIBLE.
  - A managed Postgres with the default of 1 s would make victims return before the cycle breaks (the livelock `ab4300c` fixed).
  - Fix: document `deadlock_timeout=100ms` as a deployment requirement in `system-design.md`, or read `SHOW deadlock_timeout` at startup.
- **L-5. Pool exhaustion returns 500 `KUSS_INTERNAL_ERROR` (as phase 05 specifies).** `AppointmentServiceImpl.java:197-200`.
  - A 503 with `Retry-After` would tell clients the overload is temporary.
  - This changes the API contract, so it is **a developer decision**, not a defect.
- **L-6. Unbounded dates on read endpoints.** `AppointmentServiceImpl.java:370-381` (`listForDay`: no horizon check either way) and `AvailabilityServiceImpl.java:87-92` (no lower bound). PLAUSIBLE.
  - A date such as `-999999-01-01` produces an Instant outside Postgres' `timestamptz` range. That gives a 500 with an ERROR stack trace from user input: the log-spam case RT-13b was meant to close, but on reads.
  - Fix: apply the horizon (and a lower bound) in `listForDay`, and a lower bound in availability.
- **L-7. Creating a partition fails if DEFAULT already holds rows for that day.** `MaintenanceServiceImpl.java:96-107`. PLAUSIBLE.
  - This happens only after the job misses at least 8 consecutive days.
  - `runStep` logs the failure, and the day stays in DEFAULT until the 14-day delete. Acceptable for V1.
- **L-8. The audit records status 200 if an exception escapes the advice.** `RequestAuditFilter.java:106`. PLAUSIBLE.
  - For example, a response-serialization failure after the handler: the `finally` block reads the status before the container's error dispatch.
  - Rare; acceptable.
- **L-9. Convention gaps.**
  - `RequestAuditMapper.java:12` uses `new RequestAuditKey(...)` (C-4); fix with `@Builder` on the record.
  - The literal `/api/v1/appointments` appears in three classes (C-9); fix with an `ApiPathConstants` class, or keep it and record the exception.

### Not defects (checked)

- **Constraint routing:** `SqlErrorUtils` walks the cause chain to `PSQLException`; a 40P01 has no constraint name and is classed `deadlock`.
- **Hibernate exception translation:**
  - `AppointmentWriteRepository` is a `@Repository`, so a 23P01 or 40P01 arrives as a `DataAccessException`.
  - In `IdempotencyServiceImpl`, a 23505 is converted to `SchedulerException` inside the transaction, so the attempt rolls back and the 409 is not retried.
- **Timezone and DST:**
  - The grid is stepped in Instants from the local open time.
  - DST gaps and overlaps are resolved by `atZone`. They can only matter if opening hours fall between 02:00 and 03:00.
  - The horizon is measured in the dealership zone, and the technician-load day is the dealership-local day (`:241`).
  - Covered by `TimeSlotUtilsTest` (London BST, New York DST start, past starts today).
- **Input validation:**
  - Path variables are `UUID`.
  - The key `@Pattern` matches `VARCHAR(128)`.
  - The body uses `@Valid` with `@Size(max=40)`.
  - The availability `serviceType` has no length bound, but it only reaches a PK lookup and returns 404.
- **Leaks:**
  - Every `SchedulerException` message is a constant.
  - The 500 path logs server-side and returns a generic message.
  - Partition DDL builds names from `LocalDate` and from a regex filter.

### Developer decisions on the open questions (via the orchestrator, 2026-10-31)

| # | Topic | Agent selection | Developer decision (after review, 2026-10-31) | Reason |
|---|---|---|---|---|
| DD-1 | M-1: 40P01 retry bound | Phase 05 / `ab4300c`: `MAX_ATTEMPTS = 100`, exponent from the overall attempt, sleep before every retry, no total deadline | ADD a 10 s wall-clock deadline per booking request; the backoff exponent counts deadlocks only; no sleep after the last attempt. **Adds** | Without it, one request can run 10-18 minutes after the client has given up |
| DD-2 | L-5: overload status | Phase 05 §Key Insights: no free connection → `500 KUSS_INTERNAL_ERROR`, WARN without stack trace | CHANGE the contract: overload (DB pool exhausted OR the 10 s deadline of DD-1 hit) → `503` + `Retry-After`, new code `KUSS_SERVICE_BUSY`. The idempotency key is not stored (the claim rolls back), so a same-key retry is safe. **Overrides** phase 05's "500 KUSS_INTERNAL_ERROR on pool timeout" | 500 reads as a non-retryable server bug; overload is temporary and retryable |

### Fix list (for the fix worker; no code changed in this review)

| # | Fix | Files | Tests |
|---|---|---|---|
| F-1 (H-1) | Remove `KUSS_IDEMPOTENCY_IN_FLIGHT` and `KUSS_IDEMPOTENCY_KEY_MISMATCH` from `TRACKED_SERVICE_TYPE_RESULTS` (they then tag `unknown`) | `AppointmentServiceImpl.java:82-88` | New `MetricsIT` case: a mismatch with `serviceTypeCode=ZZZ` tags `service_type=unknown,result=KUSS_IDEMPOTENCY_KEY_MISMATCH` |
| F-2 (M-1, DD-1) | Separate deadlock counter for the exponent; 10 s deadline from the first attempt; no sleep when the next attempt would pass the deadline or the cap; deadline hit → `KUSS_SERVICE_BUSY` | `AppointmentServiceImpl.java:59-62,191-205,263-294` (keep each method ≤ 20 lines) | Unit test of the backoff/deadline helper with a fixed `Clock`; `BookingConcurrencyIT` must stay green (re-run 3×) |
| F-3 (DD-2) | New `ErrorCode.KUSS_SERVICE_BUSY(HttpStatus.SERVICE_UNAVAILABLE)`; `CannotCreateTransactionException` and deadline → `KUSS_SERVICE_BUSY`; new message in `ErrorMessageConstants`; add `KUSS_SERVICE_BUSY` to `BOOKING_RESULTS` (pre-registered meter), not to `TRACKED_SERVICE_TYPE_RESULTS` | `ErrorCode.java:5-14`, `AppointmentServiceImpl.java:72-81,197-200,416-419`, `ErrorMessageConstants.java` | `SchedulerGlobalHandlerTest.should_return_problem_with_status_and_code_for_each_scheduler_error_code` gets the new code; a new handler test for `Retry-After` |
| F-4 (DD-2) | `SchedulerGlobalHandler` adds a `Retry-After` header for `KUSS_SERVICE_BUSY`. Proposed value: `1` second, a constant in `HeaderConstants` (header name) plus a `private static final` value | `SchedulerGlobalHandler.java:29-32,131-148`, `HeaderConstants.java` | Handler test asserts `503`, `code=KUSS_SERVICE_BUSY`, `Retry-After: 1`; audit row stores `KUSS_SERVICE_BUSY` (existing `RequestAuditFilter` path, no change) |
| F-5 (M-2) | Wrap the audit task: `() -> { try { write(audit); } catch (RuntimeException ex) { reject(audit); } }` | `RequestAuditFilter.java:87-93` | `RequestAuditFilterTest`: a service whose `write` throws counts one failure |
| F-6 (L-3) | `@Transactional(timeout = 5)` on `cancel` | `AppointmentServiceImpl.java:342` | none (config) |
| F-7 (L-6, optional) | Bound the `listForDay` date (horizon plus a lower bound) and add a lower bound for availability dates | `AppointmentServiceImpl.java:370-381`, `AvailabilityServiceImpl.java:87-92` | `AppointmentApiIT` / `AvailabilityApiIT` 400 for far dates |
| F-8 (L-9, optional) | `@Builder` on `RequestAuditKey`; API path constant | `RequestAuditKey.java`, `RequestAuditMapper.java:12`, the 3 classes in C-9 | none |

**Docs and plan tables to update for DD-1/DD-2 (the orchestrator routes these):**

| File | Where | Owner |
|---|---|---|
| `docs/system-design.md` | `:226` (pool exhaustion → 500) and the error-code table near `:311` (add `KUSS_SERVICE_BUSY` 503; trim "no DB connection" from `KUSS_INTERNAL_ERROR`) | KUSS-06 docs worker (file is untracked) |
| `docs/brainstorm/first-draft-high-level.md` | §8 error-code table `:448-449` (add a row) and §9 metrics `:464` (result tag set); record DD-1/DD-2 under §12 | brainstorm draft owner (Antigravity) |
| `plans/261030-KUSS-02-unified-service-scheduler/phase-05-booking-concurrency.md` | `:16` (500 on pool timeout → 503 `KUSS_SERVICE_BUSY`), `:45-49` snippet | plan owner |
| `plans/261030-KUSS-02-unified-service-scheduler/phase-03-error-handling.md` | `ErrorCode` table and enum snippet `:28,42-43` | plan owner |
| `plans/261030-KUSS-02-unified-service-scheduler/phase-07-observability-and-audit.md` | `:20` (tracked-result list, F-1) and `:29` (result tag set) | plan owner |
| `plans/261030-KUSS-02-unified-service-scheduler/plan.md` | Execution and review decisions: add DD-1/DD-2 in the agent-selection / developer-decision format | plan owner |
| `docs/code-standards.md` | no error-code table, so nothing to change | — |
| `docs/visualization/kuss-design.html` | error catalog (4 references to `KUSS_INTERNAL_ERROR`); found by Antigravity in A-029 | KUSS-03 visualization owner |

## Cross-review outcome (Antigravity, Gemini 3.8 Flash)

Thread: `claude-to-antigravity.md` C-023 → `antigravity-to-claude.md` A-028 (`Status: resolved`) → C-024 (`Status: resolved`).

| Question | Antigravity (A-028) | Result |
|---|---|---|
| Q1 H-1 severity and fix | Agrees it is High. Fix: drop `KUSS_IDEMPOTENCY_IN_FLIGHT` and `KUSS_IDEMPOTENCY_KEY_MISMATCH` from `TRACKED_SERVICE_TYPE_RESULTS`, so they record as `unknown` | Unchanged; fix confirmed |
| Q2 A-5 PARTIAL | Agrees: the exponent should count deadlocks, the last-attempt sleep is wasted, and a deadline is missing | Unchanged |
| Q3 5 s statement timeout covers EXCLUDE waits | Agrees: Hibernate applies `setQueryTimeout`; pgjdbc sends a cancel, and the waiter aborts with 57014 | Holds. A timed-out wait is not in `RETRYABLE_STATES`, so it returns 500 |
| Q4 RT-4, A-11, C-9 statuses | Agrees with ALIGNED / ALIGNED / PARTIAL. On A-11 it cites draft §6.7 (`DELETE … < now() - 24 h` on a daily job), so 24-48 h is the planned behaviour | Unchanged; L-2 stays a doc note |
| Q5 TZ/DST and idempotency races | No defects; agrees with M-2 and its fix (wrap the audit task, `reject` on failure) | Unchanged |

**Agreed by both agents:** 70 ALIGNED, 5 PARTIAL, 0 DEVIATION, 2 N-A; 0 Critical, 1 High, 2 Medium, 9 Low.

### Fix-list review (C-025 → A-029 → C-026)

| Question | Antigravity (A-029) | Result |
|---|---|---|
| Q6: per-attempt timeout vs the 10 s deadline | Keep the 5 s per attempt. Check the deadline before each attempt and before each sleep, so the worst case is about 15 s. Changing `setTimeout` on the shared `TransactionTemplate` per call is not thread-safe | Agreed. F-2 = deadline check before each attempt and each sleep; worst case ≈ 10 s + one 5 s attempt |
| Q7: `Retry-After` value | A fixed `1` is fine | F-4 unchanged |
| Q8: `KUSS_SERVICE_BUSY` in `BOOKING_RESULTS`, not tracked | Agreed | F-3 unchanged |
| Q9: docs routing | Adds `docs/visualization/kuss-design.html`; Antigravity records DD-1/DD-2 in the draft §8.1, §9, §12 | Row added to the routing table |

**Final:** both agents agree on the alignment table, the findings, DD-1/DD-2 and fix list F-1..F-8. The work is ready for a fix worker.
