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
Stack (research-01): Boot 4.0.9, Java 21 virtual threads, Maven wrapper, Postgres 17, Flyway, JPA reads + `JdbcClient` writes, Testcontainers 2.0.5, springdoc 3.2.0, Jackson 3.

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
Binding for KUSS-04, full text in `docs/code-standards.md` and `.claude/rules/java-conventions.md`:
- No inline comments (`//`, `/* */`) in main or test code; descriptive Javadoc only, required on core business methods (booking, availability, cancellation, idempotency, audit). No `ponytail:` marker comments: shortcuts are recorded in this plan (Out of scope) or docs. The `//` notes inside this plan's code snippets are for the reader only; don't copy them into code.
- Test methods are snake_case with prefix `should_` (`should_return_409_when_vehicle_already_booked`), no `// given/when/then`.
- Entities use Lombok `@Getter @Setter @Builder` + protected no-args / private all-args constructors, never `@Data`.
- Own domain types (entities, DTOs, value objects) are built with `X.builder()...build()`, never `new X(params)`; JDK/framework types and exceptions keep `new`.
- Mapping lives in MapStruct `*Mapper` interfaces (`componentModel = "spring"`), never inside DTOs (no `from(...)` / `toEntity()`).

## Draft corrections (C-017 gaps → resolution → phase)
| C-017 | Resolution | Phase |
|---|---|---|
| #1 retry in one tx | Whole tx per attempt via `TransactionTemplate`; no savepoints/NESTED (Hibernate keeps failed state, researcher-02 §2, A-022 #1); writes via `JdbcClient` | 5 |
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

## Dependencies / risks
- Local machine runs **Podman** (no Docker CLI, no JDK). The orchestrator provisions JDK 21 (Homebrew), starts `podman-machine-default` and exports the Testcontainers env (`DOCKER_HOST`, `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`, Ryuk) before `/implement`; phase 01 step 0 verifies. Images are fully qualified; `podman compose` and `docker compose` are both supported. Wrapper comes from start.spring.io (no global Maven). <!-- Updated: Validation Session 1 -->
- Checkstyle plugin 3.6.0 bundles Checkstyle 9.3 (no Java 21 pattern syntax) → override core dependency (phase 01).
- Exclusion-constraint wait + retry semantics: `BookingConcurrencyIT` is the release gate; `SchemaConstraintsIT`'s two-connection case gives the first signal in phase 02. Concurrent conflicting inserts can deadlock (40P01) by design → retried, `deadlock_timeout=100ms`.
- Single shared Testcontainers context → ITs truncate `appointment` + `idempotency_key` before each test.

## Planner open questions (resolved by Claude Code, 2026-10-30)
- No `*Entity`/`*Repository` for `idempotency_key` / `request_audit`: nothing reads them via JPA; writes are `JdbcClient` (YAGNI). Kept.
- Replays count as `result=confirmed`; DTO-level 400s visible via `http.server.requests` + audit only. Kept.
- `service_type` tag bounded by one PK `existsById` lookup per booking (`unknown` otherwise). Superseded by Red Team #13: tag = request code only for post-validation outcomes, no lookup.
- `ddl-auto: validate` kept (catches mapping drift).
- ITs use dates relative to `clock` (today + 7 days). Kept.

## Cross-Review (Antigravity)
Thread: `docs/agent-discussion/claude-to-antigravity.md` C-017–C-021 ↔ `antigravity-to-claude.md` A-022–A-024. Reviewer: Antigravity (Gemini 3.8 Flash, high).
| Entry | Point | Outcome | Applied to |
|---|---|---|---|
| A-022 #1 | Hibernate marks tx rollback-only on 23P01 → write path off JPA | Already in plan (JdbcClient + whole-tx retry) | Phase 5 |
| A-022 #2 | Claim-first needs nullable response columns | Already in plan | Phase 2 |
| A-023 Q1–Q7 | Retry order, claim-first + IN_FLIGHT test, candidate SQL, seed K, cancel order, YAGNI | Agreed, no change | — |
| A-023 #1 | Startup partition window (rows land in DEFAULT before partitions exist) | Accepted: `SmartLifecycle` phase `Integer.MIN_VALUE`; rejected its `ContextRefreshedEvent` alternative (fires after server start) | Phase 7 |
| A-023 #2 | Cancel loads a JPA entity then updates via JdbcClient | Accepted: `findForUpdate` via JdbcClient | Phase 6 |
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
