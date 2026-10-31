---
title: "Unified Service Scheduler — backend implementation plan"
description: "Build the Scenario A booking API (bay + qualified tech for full duration) on Spring Boot 4 + PostgreSQL with GiST exclusion, idempotency, audit and KUSS_* errors."
status: pending
priority: P1
effort: 29h
issue: "#4 KUSS-04 (planned under #2 KUSS-02)"
story: KUSS-04
branch: feature/KUSS-04-unified-service-scheduler
tags: [feature, backend, database, api, critical]
created: 2026-10-30
---

# Unified Service Scheduler — Implementation Plan

## Overview
Implements approved design `docs/brainstorm/first-draft-high-level.md` (Approach A). Planned under KUSS-02; all code ships in KUSS-04.
**All phases ship on one branch `feature/KUSS-04-unified-service-scheduler`; each phase = ≥1 `[KUSS-04]` commit.** TDD: every phase writes its failing tests first.
Stack (research-01): Boot 4.0.9, Java 21 virtual threads, Maven wrapper, Postgres 17, Flyway, JPA reads + `JdbcClient` writes, Testcontainers 2.0.5, springdoc 3.2.0, Jackson 3. <!-- Superseded: developer decision 2026-10-31, JPA only (see Conventions table) -->

## Phases
| # | Phase | Story | Status | Effort | Link |
|---|-------|-------|--------|--------|------|
| 1 | Project skeleton | KUSS-04 | Pending | 3h | [phase-01](./phase-01-project-skeleton.md) |
| 2 | Schema and seed | KUSS-04 | Pending | 4h | [phase-02](./phase-02-schema-and-seed.md) |
| 3 | Error handling | KUSS-04 | Pending | 2h | [phase-03](./phase-03-error-handling.md) |
| 4 | Availability check + catalog reads | KUSS-04 | Pending | 5h | [phase-04](./phase-04-availability-check.md) |
| 5 | Booking concurrency + idempotency | KUSS-04 | Pending | 6h | [phase-05](./phase-05-booking-concurrency.md) |
| 6 | Appointment API + OpenAPI | KUSS-04 | Pending | 3h | [phase-06](./phase-06-appointment-api.md) |
| 7 | Observability and audit | KUSS-04 | Pending | 6h | [phase-07](./phase-07-observability-and-audit.md) |

Order is strict (each phase builds on the previous). Exit gate per phase: `./mvnw verify` green.

### Execution and review <!-- Updated: Validation Session 1 -->
- Implementer: Claude worker on Haiku 5.5 high (`claude-haiku-5-5`) via `/implement --auto`. Each phase must pass `./mvnw verify` before the next starts; no user pause between phases.
- After phase 07: one cross-review by Antigravity (Gemini 3.8 Flash) and a Claude Opus 5.5 high worker, discussing through `docs/agent-discussion/`. Then fix Critical/High and re-verify.
- Git: local `[KUSS-04]` commits per phase on `feature/KUSS-04-unified-service-scheduler`. **No push** to `origin` (github.com/kangourouuu/keyloop-unified-service-scheduler) until the user approves after the cross-review. PR body: `Closes #4` (issues #1–#6 = KUSS-01..06).

- Agent selection: "Order is strict". **Developer decision (after review, 2026-10-31):** phase 07 splits into 7a (async audit + partition/idempotency maintenance, built in parallel by another Haiku worker in its own worktree after P2+P3) and 7b (metrics, probes, logging: the main implementer after P6). The orchestrator cherry-picks 7a onto this branch — overrides. **Reason:** 7a has no code dependency on P4-P6. (The P3 parallel run was superseded because you built P3 first.)
- Agent selection (KUSS-04 implementer, d536d09): bay -> technician PESSIMISTIC_WRITE locks before insert, to stop a 40P01 storm. Developer decision (after review, 2026-10-31): reverted; Approach A, EXCLUDE + retry only, is the V1 decision. Lock ordering is a ROADMAP item for scaling, triggered when scheduler.booking.gist_retries{conflict_type=deadlock} climbs under real load — overrides the implementer deviation. Fix applied: PostgreSQL breaks one exclusion-index wait cycle per deadlock_timeout while each victim retried at once and re-inserted a conflicting index entry, so 17-18 inserters stayed blocked in a livelock; a 40P01 victim now backs off for a randomized wait of 1.6-3.2 s that doubles once up to 3.2-6.4 s before retrying (see the decision below), so victims return slower than PostgreSQL breaks cycles. This backoff only works with `deadlock_timeout=100ms`, so that setting is a required database setting for every deployment.
- DD-1. Agent selection (phase 05 / `ab4300c`): `MAX_ATTEMPTS = 100`, backoff exponent from the overall attempt, a sleep before every retry, no total deadline. Developer decision (after review, 2026-10-31): a 10 s wall-clock deadline per booking request, checked before each attempt and before each 40P01 sleep (worst case about 10 s plus one 5 s attempt); the exponent counts deadlocks only; no sleep after the last attempt or when the wait would pass the deadline — adds. Reason: without it one request could run 10-18 minutes after its client gave up.
  - Fix worker note (2026-10-31, for developer review; historical, the wait no longer starts at deadlock_timeout, see the decision below): with DD-1 as written, the first wait started at deadlock_timeout (100-200 ms) and `BookingConcurrencyIT` failed 3/3 runs: PostgreSQL killed one victim per 100 ms out of a knot of about 17 waiters, victims came back before the knot drained, and 11-16 requests per run hit the deadline as 503. The first wait is now 1.6-3.2 s (longer than a 20-request knot takes to drain), doubling once to the unchanged 3.2-6.4 s cap; the exponent still counts deadlocks only. The gate passed 5/5 in a row with 0 busy responses. Cost: a deadlock victim waits at least 1.6 s.
  - **Developer decision (after review, 2026-10-31):** accept a first deadlock-retry wait of 1.6–3.2 s (doubling once, up to the 3.2–6.4 s cap), instead of starting at deadlock_timeout — overrides the 100 ms start, keeps DD-1 (10 s deadline). **Reason:** measured 5/5 green with zero 503s and the slowest burst at 4.8 s; only burst deadlock losers wait, normal bookings never do.
- DD-2. Agent selection (phase 05 §Key Insights): no free connection → 500 `KUSS_INTERNAL_ERROR`, WARN without stack trace. Developer decision (after review, 2026-10-31): overload (DB pool exhausted or the DD-1 deadline reached) → 503 with `Retry-After: 1` and new code `KUSS_SERVICE_BUSY`, pre-registered in the booking result set and tagged `service_type=unknown`; the claim rolls back, so a same-key retry is safe; the retry cap stays 500 — overrides phase 05's 500 on pool timeout. Reason: 500 reads as a non-retryable server bug; overload is temporary and retryable.
- Trace IDs. Agent selection: traceId in prod logs as a phase-07 success criterion. Developer decision (after review, 2026-10-31): the MDC wiring alone is accepted; traceId/spanId appear on WARN/ERROR lines, no per-request INFO line — overrides the criterion. Reason: no per-request log noise.
- L-2 exact TTL. Agent selection (draft §6.7, phase 07): a daily job deletes idempotency keys older than 24 h, so a key lives 24-48 h and expiry is not checked on read. Developer decision (after review, 2026-10-31): a key older than 24 h is treated as absent on read; the claim deletes it and stores the new request in the same transaction — adds. Reason: the 24 h TTL holds exactly, whatever the purge schedule.
- F-7 date bounds. Agent selection (phases 04 and 06): availability enforces only the 90-day horizon and answers a past date with 200 and empty slots; the day list has no bound. Developer decision (after review, 2026-10-31): availability accepts today .. today + 90 days and the day list accepts today − 365 .. today + 90 days, in the dealership zone; outside → 400 `KUSS_VALIDATION_FAILED` — adds the bounds, overrides the 200 for a past availability date. Reason: availability for the past is meaningless; the day list keeps a year of history; far dates fell outside `timestamptz` and returned 500.
- BUG-1 (KUSS-05 retest). Agent selection: only booking mapped a pool timeout to 503 (DD-2); cancel and all reads returned 500 `KUSS_INTERNAL_ERROR`. Developer decision (after review, 2026-10-31): cancel and every read endpoint return 503 `KUSS_SERVICE_BUSY` with `Retry-After: 1` on a pool timeout, mapped once in `SchedulerGlobalHandler` — adds, closes the DD-2 gap. Reason: overload is temporary and retryable on every endpoint, not only on booking.
- BUG-2 (KUSS-05 retest). Agent selection: a database lock stall during booking surfaced as 500 with an ERROR stack trace, and read transactions had no timeout. Developer decision (after review, 2026-10-31): a statement timeout during booking returns 503 `KUSS_SERVICE_BUSY` (counted as such in the booking metric; the claim rolled back, so a same-key retry works); every read transaction is `@Transactional(readOnly = true, timeout = 5)` and a timeout maps to 503 — adds the read timeout, overrides the 500. Reason: a stalled database is as retryable as a full pool.
- BUG-3 (KUSS-05 retest). Agent selection: the 400 `detail` for `POST /appointments` named the parameter `request`, once per violation. Developer decision (after review, 2026-10-31): the detail names each invalid field once (`serviceTypeCode: size must be between 0 and 40`), with the bean-validation message and no Java type names (Red Team #9 still applies) — overrides. Reason: a client needs the field name to fix the request.
- BUG-4 (KUSS-05 retest). Agent selection: `scheduler.booking.requests` counted only failures raised inside `book()`. Developer decision (after review, 2026-10-31): boundary 400s on `POST /appointments` (missing `Idempotency-Key`, body validation, malformed JSON, header validation) are also counted with `service_type=unknown`, `KUSS_IDEMPOTENCY_KEY_MISSING` is pre-registered with the other results (Red Team #11) — adds. Reason: the metric must match the 400s that clients see.
- BUG-5 (KUSS-05 retest). Agent selection: Spring Boot's default error page answered `GET /error` with `{"status":999}` and Tomcat answered rejected requests (`%00`, `%ZZ`, oversized header) with HTML. Developer decision (after review, 2026-10-31): a custom `ErrorController` plus a Tomcat error report valve return `application/problem+json` with a `KUSS_*` code; container-level rejections never reach the request filter, so their body has no `requestId` and they are not audited — overrides. Reason: draft §8.1, every non-2xx is a ProblemDetail.
- BUG-6 (KUSS-05 retest). Agent selection: the audit filter skipped `/actuator/` but not the index `GET /actuator`. Developer decision (after review, 2026-10-31): `GET /actuator` is not audited either — overrides. Reason: audit scope is "all paths except actuator and API docs".
- BUG-7 (KUSS-05 retest). Agent selection: no request-body size limit (a 60 MB valid booking was accepted). Developer decision (after review, 2026-10-31): not fixed, roadmap — a known limitation. Reason: out of V1 scope.
- Cancel locking. Agent selection: cancel locks the row with SELECT ... FOR UPDATE / PESSIMISTIC_WRITE (A-023 #2). Developer decision (after review, 2026-10-31): no explicit locks anywhere; cancel is one atomic conditional UPDATE ... WHERE status = CONFIRMED AND start_time > now — overrides. Reason: the developer's original rule, "do not lock table, use atomic conditional update".
- Data access. Agent selection: booking and audit inserts go through `AppointmentWriteRepository` / `RequestAuditWriteRepository`, raw `EntityManager.persist`. Developer decision (after review, 2026-10-31): Spring Data JPA only — both inserts use `saveAndFlush` on the entity's repository, the entities implement `Persistable` (as `IdempotencyKeyEntity` does) so the insert stays a single INSERT; the write repositories are deleted. Only `MaintenanceServiceImpl` keeps `EntityManager` native queries for the dynamic partition DDL — overrides. Reason: no native entity manager for entity writes.

### Cut list (if over budget, cut in this order)
1. Second compose smoke run with prod logs (phase 07 step 5) → keep the curl checks only.
2. `scheduler.availability.query.duration` timer and its `MetricsIT` case (phase 07).
3. Swagger UI manual check (phase 06 step 3); the `/v3/api-docs` assertion stays.
Never cut: any Test map row, `BookingConcurrencyIT`, the vehicle precheck, idempotency. <!-- Updated: Red Team #6 -->

## Fixed decisions (draft §12, not re-litigated)
- Approach A: partial `EXCLUDE USING gist` on bay/tech/vehicle; re-query + retry until no free pair; random free bay; tech with fewest confirmed minutes that day.
- `Idempotency-Key` required on book AND cancel; `idempotency_key` row in the same tx; 24h cleanup; replay / 422 mismatch / 409 in-flight.
- `request_audit`: async, `REQUIRES_NEW`, daily partitions, BRIN, 14-day retention, all `/api/**` incl. reads.
- One `SchedulerGlobalHandler`: RFC 9457 `ProblemDetail` + `code` (KUSS_*) + `requestId`; code mirrored to `request_audit.error_code`.
- Minimal POST body `{dealershipId, vehicleId, serviceTypeCode, desiredStartTime}`; customer derived from vehicle.
- Extra read endpoints + cancel; no auth; request signing roadmap only; no Redis/RabbitMQ/WebSocket in code or compose.

## Conventions added after validation (developer decisions, 2026-10-31)
Binding for KUSS-04. Full text: `docs/code-standards.md` (§1–§5 and its Developer decisions log) and `.claude/rules/java-conventions.md`.

| # | Topic | Agent selection | Developer decision (after review, 2026-10-31) | Reason |
|---|---|---|---|---|
| 1 | Comments | `development-rules.md`: comments only where intent isn't obvious, plus `ponytail:` markers for deliberate shortcuts | No inline comments (`//`, `/* */`) in main or test code; descriptive Javadoc only, required on core business methods (booking, availability, cancellation, idempotency, audit); no `ponytail:` markers, shortcuts go in this plan (Out of scope) or docs; checked in review, not by Checkstyle. **Overrides** | Names explain the code; Javadoc documents the contract |
| 2 | Test method names | none (plan used camelCase, e.g. `contextLoads()`) | snake_case with prefix `should_` (`should_return_409_when_vehicle_already_booked`), no `// given/when/then`. **Adds** | The name states the expected behaviour |
| 3 | Entities | none (plain JPA entities, no Lombok) | Lombok `@Getter @Setter @Builder` + protected no-args / private all-args constructors; never `@Data`, `@EqualsAndHashCode`, `@ToString`. **Adds** | `@Data` breaks lazy loading and loops on relations |
| 4 | Object creation | constructors / record canonical constructors (`new AppointmentResultDto(201, …)`) | Own domain types (entities, DTOs, value objects) via `X.builder()…build()`; JDK/framework types and exceptions keep `new`. **Overrides** | Named fields read better than positional arguments |
| 5 | Mapping | `*Dto` records own the mapping (`static from(XEntity)`, `toEntity()`) | MapStruct `*Mapper` interfaces (`componentModel = "spring"`); no mapping code inside DTOs. **Overrides** | DTOs stay pure data; mapping is generated and testable in one place |
| 6 | Formatting | none (Checkstyle `MethodLength` only) | Spotless `check` fails `./mvnw verify`: google-java-format (main + test), unused imports, import order, `sortPom`, Flyway SQL, YAML, Markdown (not `docs/brainstorm`, `docs/agent-discussion`, `plans`). **Adds**. 2026-10-31: Markdown stays in the verify gate after the docs-overlap escalation — confirms; the docs worker runs spotless:apply after each edit. | One format for everyone, enforced by the build |
| 7 | Schema changes | Flyway `V1`/`V2` + `ddl-auto: validate` (phases 01–02) | `validate` **confirmed**; **adds** `generate-ddl: false`, `clean-disabled: true`, never edit a committed migration, every schema change via Flyway; only exception: phase 07's job creating/dropping `request_audit` day partitions (never alters columns, indexes, constraints) | One auditable source of schema history; daily partitions cannot be versioned migrations |
| 8 | Migration file names | `V1__schema.sql`, `V2__seed.sql` | `V<version>__<description>.sql`: sequential integer, lower snake_case description starting with a verb (`V1__create_schema.sql`, `V2__insert_seed_data.sql`). **Overrides** | The file name says what the migration does |
| 9 | Constants | Literals inline at each use; request attribute keys as constants inside `RequestAuditFilter`; SQL as `private static final` text blocks in each repository (red-team #13) | Every error/detail message and every name shared across classes (headers, request attribute/MDC keys, metric names) lives in `com.keyloop.scheduler.common.constants` as `*Constants` classes (`ErrorMessageConstants`, `HeaderConstants`, `RequestConstants`, `MetricConstants`; final class, private constructor). A literal used in one class only stays `private static final` there. SQL stays in its repository (red-team #13 **confirmed**). **Overrides** inline messages | One place per message and name; no drift between duplicates |
| 10 | Data access | JPA reads + `JdbcClient` writes (plan decision 1; cross-review A-024 agreed: no dirty checking, exact SQLState on the write statement) | JPA only in `src/main`, no `JdbcClient`: Spring Data repositories with JPQL `@Query` (`nativeQuery=true` only where JPQL cannot express it); booking, idempotency claim, audit writes via entities with `saveAndFlush` inside each per-attempt transaction (entities implement `Persistable`), and cancel as one atomic conditional JPQL `UPDATE` (`status = CONFIRMED AND start_time > now`), with no explicit lock; SQLState read from the exception cause chain; partition DDL in `MaintenanceService` as `EntityManager` native queries (names only from the date formatter or the regex, rule unchanged); tests may keep raw JDBC connections for lock and race harnesses. **Overrides** | One data-access style; JPQL/`@Query` instead of hand-written JDBC |
| 11 | Package layout | none (flat packages per domain, mixed roles) | Layout by role per domain: `<domain>/{controller, service, service/impl, repository, entity, dto, mapper, config, util}`; a class sits in the subpackage that matches its suffix; cross-package classes are `public`. Shared code stays in `common`. **Adds** | A reader finds a class by its role, not by scanning one flat package |

The `//` notes inside this plan's code snippets are for the reader only; don't copy them into code.

## Draft corrections (C-017 gaps → resolution → phase)
| C-017 | Resolution | Phase |
|---|---|---|
| #1 retry in one tx | Whole tx per attempt via `TransactionTemplate`; no savepoints/NESTED (Hibernate keeps failed state, researcher-02 §2, A-022 #1); writes via `JdbcClient` | 5 | <!-- Superseded: developer decision 2026-10-31, JPA only (see Conventions table) -->
| #2 IN_FLIGHT unreachable | Claim-first: `SELECT` key, else plain `INSERT` claim; 23505 → 409; response cols nullable; NO `lock_timeout` (would cap EXCLUDE waits) | 2, 5 |
| #3 audit PK / partitions | `PRIMARY KEY (id, occurred_at)`, column `occurred_at`; D..D+7 at startup + daily, `IF NOT EXISTS`, failures logged | 2, 7 |
| #4 audit starves pool | Bounded executor (2/2/1000), rejection counted + WARN, dispatched after business tx | 7 |
| #5 missing core tests | See Test map | 2, 4, 5, 6 |
| #6 distinct vehicles/keys in burst | `BookingConcurrencyIT` uses N distinct vehicles + keys, bay-bound and tech-bound variants | 5 |
| #7 time contract | `desiredStartTime` ISO-8601 with offset → `Instant`; grid/hours in dealership `ZoneId`; availability shape fixed | 4, 6 |
| #8 past-time untestable | `Clock` bean everywhere "now" is used; past rows seeded via JDBC | 1, 6 |
| #9 Boot 4 pitfalls | `spring-boot-starter-flyway` + `flyway-database-postgresql`, TC 2 artifacts, springdoc 3, `tools.jackson` | 1 |
| #10 hash / audit scope / jobs | Hash over canonical fields (whitespace-insensitive, overrides raw-body idea); audit every path except `/actuator/**` + API docs, matched on the servlet path (Red Team #10); all DDL/DELETE idempotent | 5, 7 |
Also: draft §7.2 "rollback sub-tx" and "insert key last" superseded by rows #1/#2; §7.4 "virtual-thread async" replaced by bounded executor.

## Test map
| Scenario (draft §12 / C-017 #5) | Test class | Phase |
|---|---|---|
| 1 Validation 400 (grid, past, hours, horizon, malformed) | `TimeSlotUtilsTest`, `AppointmentApiIT` | 4, 6 |
| 2 Missing Idempotency-Key 400 | `SchedulerGlobalHandlerTest`, `AppointmentApiIT` | 3, 6 |
| 3 Not found 404 | `AvailabilityApiIT`, `AppointmentApiIT` | 4, 6 |
| 4 Vehicle double-booking 409 (incl. full slot) | `VehicleOverlapIT`, `AppointmentApiIT` | 5, 6 |
| 5 Capacity exhaustion 409 | `BookingConcurrencyIT`, `AppointmentApiIT` | 5, 6 |
| 6 In-flight key 409 | `IdempotencyIT`, `SchedulerGlobalHandlerTest` (mapping) | 3, 5 |
| 7 Past cancel 409 | `AppointmentApiIT` | 6 |
| 8 Key mismatch 422 | `IdempotencyIT`, `AppointmentApiIT` | 5, 6 |
| 9 Internal error 500 + audited | `ErrorHandlingIT`, `RequestAuditIT` | 3, 7 |
| 10 Idempotent replay, one row | `IdempotencyIT`, `AppointmentApiIT` | 5, 6 |
| 11 Burst N vs K → K×201 | `BookingConcurrencyIT` (N=20), `AppointmentApiIT` (HTTP N=10,K=3) | 5, 6 |
| Sweep, bay/tech-bound, unqualified/other dealership ignored | `AvailabilitySweepTest`, `BookingConcurrencyIT` | 4, 5 |
| Back-to-back `[)`, cancelled frees window, two-connection race | `SchemaConstraintsIT`, `AvailabilitySweepTest` | 2, 4 |
| PDF Req 3: booking links customer, vehicle, technician, bay | `BookingConcurrencyIT` | 5 |
| Cancel frees capacity (re-book) | `BookingConcurrencyIT` (JDBC cancel), `AppointmentApiIT` (HTTP) | 5, 6 |
| DST / zone / past slots today | `TimeSlotUtilsTest` | 4 |

## Out of scope
- Docs deliverables (system design doc, README, AI log, video) → KUSS-06 docs worker.
- Retest / bug report → KUSS-05. cURL examples doc → KUSS-06 (springdoc OpenAPI is the client stub).
- Auth, request signing, Redis, RabbitMQ, WebSocket, admin CRUD, shifts/breaks, pagination. `GET /customers` is unbounded and public: paginate and scope by auth once customers are real data.
- `MAX_ATTEMPTS` booking retry cap: only trips on a bug where the candidate query and the constraint predicate disagree; the loop normally ends when candidates run out.
- Rate limiting: none in V1, so anyone can read and cancel everything; the 90-day horizon only bounds how far ahead capacity can be held (Red Team #14).
- Lock ordering before insert: roadmap for scaling (trigger: deadlock retry metric), not V1.

## Dependencies / risks
- Local machine runs **Podman** (no Docker CLI, no JDK). The orchestrator provisions JDK 21 (Homebrew), starts `podman-machine-default` and exports the Testcontainers env (`DOCKER_HOST`, `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`, Ryuk) before `/implement`; phase 01 step 0 verifies. Images are fully qualified; `podman compose` and `docker compose` are both supported. Wrapper comes from start.spring.io (no global Maven). <!-- Updated: Validation Session 1 -->
- Checkstyle plugin 3.6.0 bundles Checkstyle 9.3 (no Java 21 pattern syntax) → override core dependency (phase 01).
- Exclusion-constraint wait + retry semantics: `BookingConcurrencyIT` is the release gate; `SchemaConstraintsIT`'s two-connection case gives the first signal in phase 02. Concurrent conflicting inserts can deadlock (40P01) by design → retried, `deadlock_timeout=100ms`.
- Single shared Testcontainers context → ITs truncate `appointment` + `idempotency_key` before each test.

## Planner open questions (resolved by Claude Code, 2026-10-30)
- No `*Entity`/`*Repository` for `idempotency_key` / `request_audit`: nothing reads them via JPA; writes are `JdbcClient` (YAGNI). Kept. <!-- Superseded: developer decision 2026-10-31, JPA only (see Conventions table) -->
- Replays count as `result=confirmed`; DTO-level 400s visible via `http.server.requests` + audit only. Kept.
- `service_type` tag bounded by one PK `existsById` lookup per booking (`unknown` otherwise). Superseded by Red Team #13: tag = request code only for post-validation outcomes, no lookup.
- `ddl-auto: validate` kept (catches mapping drift).
- ITs use dates relative to `clock` (today + 7 days). Kept.

## Cross-Review (Antigravity)
Thread: `docs/agent-discussion/claude-to-antigravity.md` C-017–C-021 ↔ `antigravity-to-claude.md` A-022–A-024. Reviewer: Antigravity (Gemini 3.8 Flash, high).
| Entry | Point | Outcome | Applied to |
|---|---|---|---|
| A-022 #1 | Hibernate marks tx rollback-only on 23P01 → write path off JPA | Already in plan (JdbcClient + whole-tx retry) | Phase 5 | <!-- Superseded: developer decision 2026-10-31, JPA only (see Conventions table) -->
| A-022 #2 | Claim-first needs nullable response columns | Already in plan | Phase 2 |
| A-023 Q1–Q7 | Retry order, claim-first + IN_FLIGHT test, candidate SQL, seed K, cancel order, YAGNI | Agreed, no change | — |
| A-023 #1 | Startup partition window (rows land in DEFAULT before partitions exist) | Accepted: `SmartLifecycle` phase `Integer.MIN_VALUE`; rejected its `ContextRefreshedEvent` alternative (fires after server start) | Phase 7 |
| A-023 #2 | Cancel loads a JPA entity then updates via JdbcClient | Accepted: `findForUpdate` via JdbcClient | Phase 6 | <!-- Superseded: developer decision 2026-10-31, JPA only (see Conventions table) -->
| A-023 #3 | Jackson `adjust-dates-to-context-time-zone` | Rejected (deserialization-only); offset assertions in `AvailabilityApiIT` instead | Phase 4 |
**Result:** A-024 `resolved` — plan agreed by both agents (2026-10-30).

## Red Team Review
Session 2026-10-30 (reviews) / 2026-10-31 (decisions). Reviewers: security, failure-modes, assumptions, scope (`reports/red-team-*-261030.md`, 34 raw findings → 15 after dedup, `reports/red-team-adjudication-261030.md`). Each finding was decided by the developer one by one via the coordinator.

| # | Finding | Sev | Agent disposition | Developer decision (after review, 2026-10-31) | Applied to |
|---|---|---|---|---|---|
| 1 | Concurrent conflicting EXCLUDE inserts deadlock by design (unprincipled deadlock); research-02 §7 "no cycles" wrong | High | Accept | Accepted after the root-cause explanation, confirms; `deadlock_timeout=100ms` in both Testcontainers and compose | P1, P5, research-02 §7 |
| 2 | `@ElementCollection` join column default breaks `ddl-auto: validate` | High | Accept | Accepted, confirms | P2 |
| 3 | Leaked lock-holder connection hangs the suite at `TRUNCATE` | High | Accept | Accepted, confirms | P2, P5 |
| 4 | Prod pool / tx / connection timeouts unbounded | High | Accept | Accepted, confirms | P1, P5 |
| 5 | Same scenarios tested at service level and over HTTP | High | Accept (modified) | Accepted, confirms | P5, P6, Test map |
| 6 | 26h too low, no cut list | High | Accept | Accepted, confirms: 29h (P4 5h, P7 6h) + cut list | plan, P4, P7 |
| 7 | Release gate only after 13h | High | Accept (modified) | Accepted, confirms: early two-connection test, no reorder | P2 |
| 8 | Booked vehicle + full slot → NO_CAPACITY instead of VEHICLE_ALREADY_BOOKED | Med | Accept | Accepted, confirms | P5 |
| 9 | 4xx detail leaks Java types; headers dropped; `ConstraintViolationException` unmapped | Med | Accept | Accepted, confirms | P3 |
| 10 | Raw-URI audit scope, no truncation, false security claims, VIN/plate exposed | Med | Accept | Accepted (a scope + truncation, b security text, c `CustomerDto`), confirms; security text fixed in plan files only, draft wording routed to its owner | P4, P6, P7 |
| 11 | Test brittleness bundle (audit default row, lazy meters, DDL on shared table, ns vs µs, NY time) | Med | Accept | Accepted, confirms | P2, P5, P6, P7 |
| 12 | PDF Req 3 links never asserted | Med | Accept | Accepted, confirms | P5 |
| 13 | Simplify: `candidateStarts.contains`, 90-day horizon, metric tag without lookup, trim framework tests, SQL constants | Med | Accept | Accepted (a–d incl. 90-day horizon), confirms | P2, P4, P5, P7 |
| 14 | Ops hardening (healthcheck, restart, 127.0.0.1, non-root, pinned Checkstyle, key charset, no-rate-limit note, logged drops) | Med | Accept | Accepted, confirms | P1, P6, P7, plan |
| 15 | Remove DEFAULT partition / `response_status`; management port; wrapper checksum; early-return cancel | Med | Reject | Rejected, confirms: draft §6 schema is fixed; the rest is prod hardening or breaks replay semantics | — |

**Totals:** 15 findings (7 High, 8 Medium) → 14 accepted (2 modified), 1 rejected; all 15 confirmed by the developer.
**Draft wording to correct (owner: brainstorm draft, not edited here):** `docs/brainstorm/first-draft-high-level.md` §11 #9 (line ~505) says only that there is no auth; it should state the consequence: "anyone can read all data and cancel any appointment; auth + per-dealership scoping is roadmap; no rate limiting". §8 `GET /api/v1/customers` (line ~409) should say vehicles are returned as id/make/model/modelYear only (no VIN/plate). §7 / §12 audit scope "`/api/**`" becomes "all paths except actuator and API docs" (Red Team #10).

## Validation Log

### Session 1 — 2026-10-31
**Trigger:** `/plan-story validate` after the red-team review (all 15 findings decided).
**Questions asked:** 4 (one grouped ask via the orchestrator).

#### Questions & Answers

1. **[Risk]** This machine has no JDK 21 and no Docker ('docker not found'). Testcontainers ITs and compose need both. Who provisions them?
   - Options: 1a You install JDK 21 (Temurin) + a Docker runtime (Docker Desktop or Colima) before /implement (Recommended) | 1b The implementer installs them via Homebrew (temurin@21, colima, docker) as phase 01 step 0
   - **Answer:** Other (modified 1a)
   - **Custom input:** "the ORCHESTRATOR provisions the environment before /implement. The user runs Podman Desktop, not Docker Desktop: /opt/podman/bin/podman exists, the machine podman-machine-default (libkrun) is not started, there is no docker CLI and no JDK. Plan changes: phase 01 must work under Podman. Use DOCKER_HOST=unix://<podman machine API socket> and TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock, handle Ryuk (privileged or disabled) as Testcontainers-on-Podman needs, and make the compose instructions work with 'podman compose' as well as 'docker compose'. JDK 21 via Homebrew. Record this in the Validation Log; don't install anything yourself."
   - **Rationale:** Every `*IT` and the compose smoke run depend on the container runtime. Podman also needs fully-qualified image names and Ryuk handling.
2. **[Architecture]** Spring Boot line. Research verified 4.0.9 (latest 4.0.x) with springdoc 3.2.0 and Testcontainers 2.0.5; 4.1.2 is a newer GA minor.
   - Options: 2a 4.0.9, as researched (Recommended) | 2b 4.1.2 (re-verify springdoc/TC compatibility in phase 01)
   - **Answer:** 2a, Spring Boot 4.0.9
   - **Rationale:** Keeps the researched version set; no re-verification needed.
3. **[Scope]** Implement run mode.
   - Options: 3a /implement --auto: per-phase gate is './mvnw verify' green, /review-code after phase 07 (Recommended) | 3b Pause after each phase for your review before the next
   - **Answer:** Other (modified 3a)
   - **Custom input:** "implementation is done by a Claude worker on Haiku 5.5 at high effort (claude-haiku-5-5). A cross-review follows ONCE, after phase 07, by two reviewers who discuss with each other through docs/agent-discussion: Gemini 3.8 Flash (Antigravity) and a Claude Opus 5.5 high worker. Each phase still has to pass './mvnw verify' before the next one starts; no pause between phases for the user. Then fix Critical/High and re-verify."
   - **Rationale:** A smaller implementer model makes the plan's exact file/test specs and the objective `./mvnw verify` gate load-bearing. Review happens once at the end.
4. **[Scope]** Commits and push on feature/KUSS-04-unified-service-scheduler.
   - Options: 4a Commit locally per phase ([KUSS-04] messages as in the phases); push + PR only after all 7 phases pass /review-code (Recommended) | 4b Push after each phase (incremental draft PR)
   - **Answer:** Other (modified 4a)
   - **Custom input:** "commit locally per phase with [KUSS-04] messages. After the agent cross-review the USER is the final gate: no push to any remote branch until they approve. The remote now exists: origin = github.com/kangourouuu/keyloop-unified-service-scheduler. Tickets are tracked as issues #1-#6 (KUSS-0X = #X); PRs use 'Closes #X'."
   - **Rationale:** Nothing leaves the machine before the user approves; the PR body links the GitHub issue.

#### Confirmed Decisions
- Container runtime: Podman, provisioned by the orchestrator (JDK 21 via Homebrew). Testcontainers via `DOCKER_HOST` + socket override + Ryuk privileged/disabled; `podman compose` and `docker compose` both supported; fully-qualified image names.
- Spring Boot 4.0.9 (springdoc 3.2.0, Testcontainers 2.0.5).
- Implementer: Claude worker, Haiku 5.5 high (`claude-haiku-5-5`), runs `/implement --auto`; the per-phase gate is `./mvnw verify` green; no user pause between phases.
- Review: one cross-review after phase 07 by Antigravity (Gemini 3.8 Flash) and a Claude Opus 5.5 high worker, discussing through `docs/agent-discussion/`. Then fix Critical/High and re-verify.
- Git: local `[KUSS-04]` commits per phase on `feature/KUSS-04-unified-service-scheduler`. No push to `origin` (github.com/kangourouuu/keyloop-unified-service-scheduler) until the user approves after the cross-review. The PR body says `Closes #4`.

#### Action Items
- [x] Phase 01: Podman prerequisites (step 0), Testcontainers env, Ryuk, fully-qualified images, `podman compose`
- [x] Phase 07: compose smoke via `podman compose`
- [x] plan.md: execution and review flow, git/remote policy, risks
- [ ] Orchestrator: provision JDK 21 + start `podman-machine-default` + export env before `/implement`

#### Impact on Phases
- Phase 01: Key Insights, Requirements, step 0, steps 6/9/10/11, Todo, Success Criteria, Risks (Podman).
- Phase 07: step 5 smoke command.
- Phases 02–06: no change (all `*IT` inherit the phase 01 Testcontainers setup).
