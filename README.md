# Keyloop Unified Service Scheduler

Backend for Keyloop challenge Scenario A (`KeyloopCodingChallange.pdf`). It is a REST API that books a service appointment only when a service bay **and** a qualified technician are both free for the whole duration.

- PostgreSQL enforces that rule with `EXCLUDE` constraints.
- Writes are idempotent (`Idempotency-Key`).
- Every request is audited.
- Every error carries a `KUSS_*` code.

Stack: Java 21, Spring Boot 4.0.9, Spring Data JPA, PostgreSQL 17, Flyway, springdoc-openapi, Testcontainers.

- System design: [`docs/system-design.md`](docs/system-design.md)
- Visual explainer: [`docs/visualization/kuss-design.html`](docs/visualization/kuss-design.html)
- Plan and decisions: [`plans/261030-KUSS-02-unified-service-scheduler/plan.md`](plans/261030-KUSS-02-unified-service-scheduler/plan.md)
- Code conventions: [`docs/code-standards.md`](docs/code-standards.md)
- AI collaboration evidence: [`docs/ai-collaboration-log.md`](docs/ai-collaboration-log.md)
- Video talking points: [`docs/video-outline.md`](docs/video-outline.md)

## Prerequisites

- **JDK 21** for building and testing. The Maven wrapper `./mvnw` is in the repo, so no global Maven is needed. Running only through compose needs no local JDK, because the `Dockerfile` builds inside the image.
- **Docker** (Docker Desktop, Colima, …) **or Podman** (Podman Desktop with a running machine), plus `docker compose` or `podman compose`. `podman compose` delegates to `docker-compose` or `podman-compose` ≥ 1.0.6, which `depends_on: condition: service_healthy` needs.
- `curl`, plus `jq` for the walkthrough below.

**Testcontainers on Podman.** Export these variables in the shell that runs `./mvnw` (Docker users need none of this):

```sh
export DOCKER_HOST="unix://$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}')"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
export TESTCONTAINERS_RYUK_CONTAINER_PRIVILEGED=true   # rootful machine: podman machine inspect --format '{{.Rootful}}'
# rootless machine instead: export TESTCONTAINERS_RYUK_DISABLED=true
```

## Build and test

```sh
./mvnw spotless:apply   # format Java (google-java-format), pom, SQL, YAML and Markdown
./mvnw verify           # Spotless check, Checkstyle (methods ≤ 20 lines), unit tests, then integration tests
./mvnw test             # unit tests only (*Test, no Docker needed)
```

- Spotless check and Checkstyle are bound to `validate`, so `./mvnw test` and `./mvnw verify` both fail on unformatted files or methods over 20 lines. Run `spotless:apply` first.
- **Unit tests** (`*Test`, Surefire) cover time slots and DST, the availability sweep, candidate selection, the deadlock backoff and deadline, the idempotency completion guard, the error handler, and the audit filter.
- **Integration tests** (`*IT`, Failsafe) start `docker.io/library/postgres:17` with `deadlock_timeout=100ms` through Testcontainers. They cover:
  - schema constraints, including a two-connection race;
  - the booking concurrency gate: 20 parallel requests for K free pairs give exactly K `201`;
  - vehicle overlap and idempotency (replay, `422`, in flight, TTL);
  - every HTTP status and `KUSS_*` code, including `503 KUSS_SERVICE_BUSY` on booking, cancel and reads (pool exhaustion and statement timeouts);
  - ProblemDetail bodies from `/error` and from requests Tomcat rejects;
  - request audit, partition maintenance (six-month retention) and metrics.
- **Last recorded full run:** the KUSS-04 review (2026-10-31) recorded `./mvnw -B verify` BUILD SUCCESS with 35 unit and 72 integration tests. Fixes added since bring the sources at `638222d` to 38 `*Test` and 91 `*IT` methods (two `*Test` methods are parameterized, so a run reports more tests). No full run at `638222d` is recorded yet.

## Run

```sh
docker compose up --build     # or: podman compose up --build
curl -s localhost:8080/actuator/health
docker compose down -v        # or: podman compose down -v
```

- **`db`:** PostgreSQL 17, `kuss`/`kuss`/`kuss`, published on `127.0.0.1:5432`, `deadlock_timeout=100ms`. That setting is **required for every deployment**, not only this compose file: the 1.6–3.2 s deadlock backoff is sized for it.
- **`app`:** the multi-stage image, port `8080`, profile `prod`: ECS JSON logs, Hikari pool 20, 3 s connection timeout. Port 8080 is published on all interfaces, and there is no auth, so run it on a trusted network.
- On start, Flyway creates the schema and the seed data.
- Swagger UI is at <http://localhost:8080/swagger-ui.html> and the OpenAPI document at <http://localhost:8080/v3/api-docs>.
- Health and metrics are at `/actuator/health` (`/liveness`, `/readiness`) and `/actuator/prometheus`.

## API walkthrough (curl)

Seed ids come from `src/main/resources/db/migration/V2__insert_seed_data.sql`:

- **Dealerships:**
  - London Central `00000000-0000-0000-0001-000000000001` (Europe/London, 08:00–18:00)
  - New York Midtown `00000000-0000-0000-0001-000000000002`
- **Vehicles:** `00000000-0000-0000-0005-000000000001` … `…000000000024`
- **Service types:** `OIL_CHANGE` 30 min, `TIRE_SERVICE` 60, `BRAKE_REPAIR` 90, `ENGINE_DIAGNOSTIC` 120

```sh
BASE=http://localhost:8080
LONDON=00000000-0000-0000-0001-000000000001
VEHICLE=00000000-0000-0000-0005-000000000001
DATE=$(date -v+7d +%F 2>/dev/null || date -d '+7 days' +%F)   # a day within the 90-day horizon

# 1. Catalog
curl -s $BASE/api/v1/dealerships | jq
curl -s $BASE/api/v1/service-types | jq
curl -s $BASE/api/v1/customers | jq '.[0]'          # vehicles: id, make, model, modelYear only

# 2. Availability: 30-minute starts with availableCapacity, in the dealership's offset
curl -s "$BASE/api/v1/dealerships/$LONDON/availability?date=$DATE&serviceType=OIL_CHANGE" | jq '.slots[:3]'

# 3. Book (201). desiredStartTime is ISO-8601 with an offset and must be a listed slot
KEY=demo-$(date +%s)
BODY="{\"dealershipId\":\"$LONDON\",\"vehicleId\":\"$VEHICLE\",\"serviceTypeCode\":\"OIL_CHANGE\",\"desiredStartTime\":\"${DATE}T10:00:00Z\"}"
ID=$(curl -s -X POST $BASE/api/v1/appointments -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" -d "$BODY" | tee /dev/stderr | jq -r .id)

# 4. Replay: same key and body give the stored 201 with the same id, and no second row
curl -s -i -X POST $BASE/api/v1/appointments -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" -d "$BODY"

# 5. Same key, different body: 422 KUSS_IDEMPOTENCY_KEY_MISMATCH
curl -s -X POST $BASE/api/v1/appointments -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" -d "${BODY/T10:00/T10:30}" | jq '{status, code}'

# 6. New key, same vehicle and time: 409 KUSS_VEHICLE_ALREADY_BOOKED
curl -s -X POST $BASE/api/v1/appointments -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY-2" -d "$BODY" | jq '{status, code}'

# 7. No key: 400 KUSS_IDEMPOTENCY_KEY_MISSING
curl -s -X POST $BASE/api/v1/appointments -H 'Content-Type: application/json' -d "$BODY" | jq '{status, code}'

# 8. Read it back, and the dealership's day list
curl -s $BASE/api/v1/appointments/$ID | jq
curl -s "$BASE/api/v1/dealerships/$LONDON/appointments?date=$DATE" | jq 'map({id, status, startTime})'

# 9. Cancel (200, status CANCELLED). The bay and technician are free again
curl -s -X POST $BASE/api/v1/appointments/$ID/cancel -H "Idempotency-Key: cancel-$KEY" | jq '{id, status, cancelledAt}'
```

- Every response that reaches the app carries `X-Request-Id`, which is also the `requestId` in error bodies and the key of the `request_audit` row.
- Errors are RFC 9457 `application/problem+json`: `{type, title, status, detail, instance, code, requestId}`. `/error` and requests Tomcat rejects before the app (bad encoding, oversized header) answer the same way, but the Tomcat-level body has no `requestId`.
- **Breaking change in KUSS-04:** `GET /api/v1/dealerships/{id}` returns `isActive` (was `active`) on each of `bays` and `technicians`.

|                Code                |                              HTTP                              |                                                                                                    When                                                                                                    |
|------------------------------------|----------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `KUSS_VALIDATION_FAILED`           | 400 (other framework errors such as 405/415 keep their status) | Malformed JSON or a field/parameter constraint, a bad `Idempotency-Key` format (`[A-Za-z0-9_-]{1,128}`), a start that isn't a bookable slot, or a date out of range. The `detail` names each invalid field |
| `KUSS_IDEMPOTENCY_KEY_MISSING`     | 400                                                            | No `Idempotency-Key` on book or cancel                                                                                                                                                                     |
| `KUSS_RESOURCE_NOT_FOUND`          | 404                                                            | Unknown dealership, vehicle, service type, appointment or path                                                                                                                                             |
| `KUSS_VEHICLE_ALREADY_BOOKED`      | 409                                                            | The vehicle already has an overlapping confirmed appointment                                                                                                                                               |
| `KUSS_NO_CAPACITY`                 | 409                                                            | No free bay + qualified technician for the whole duration                                                                                                                                                  |
| `KUSS_IDEMPOTENCY_IN_FLIGHT`       | 409                                                            | The same key is being processed by another request                                                                                                                                                         |
| `KUSS_APPOINTMENT_ALREADY_STARTED` | 409                                                            | Cancel at or after the start time                                                                                                                                                                          |
| `KUSS_IDEMPOTENCY_KEY_MISMATCH`    | 422                                                            | Same key, different request                                                                                                                                                                                |
| `KUSS_SERVICE_BUSY`                | 503                                                            | Any endpoint found no free DB connection or hit a statement timeout, or a booking reached its 10 s deadline. Comes with `Retry-After: 1`, and a same-key retry is safe.                                    |
| `KUSS_INTERNAL_ERROR`              | 500                                                            | Unexpected error; the stack trace is logged with the `requestId`, never returned                                                                                                                           |

**Date bounds:**

- Availability accepts today to today + 90 days, and `serviceType` must be non-blank and at most 40 characters.
- The day list accepts today − 365 to today + 90 days.
- Bookings must be in the future and at most 90 days ahead.

**No authentication in V1:** anyone can read all data and cancel any appointment, and there is no rate limiting (`docs/system-design.md` §8).

## Quality gates

1. **Before every commit:** `./mvnw spotless:apply`, then `./mvnw verify` green. Never skip or disable a failing test.
2. **Git hooks** (`git config core.hooksPath .githooks`, once per clone): branch `feature/KUSS-XX-short-description`, commit `[KUSS-XX] Description`, no `.env` files.
3. **Plans:** `/plan-story`, then red team, then validation, before any code.
4. **Implementation:** TDD, with each phase gated by `./mvnw verify`.
5. **Review:** one cross-review (Claude Opus + Gemini) after implementation. Critical/High findings are fixed and re-verified.
6. **The user is the last gate:** nothing is pushed until they approve. One squash-merged PR per ticket with `Closes #X` (KUSS-01..06 = issues #1–#6).

## AI Collaboration Narrative

Dated evidence for every claim is in [`docs/ai-collaboration-log.md`](docs/ai-collaboration-log.md). It cites discussion ids (`A-xxx`/`C-xxx`), red-team findings (`RT #n`) and review findings.

### Strategy: how the AI was guided

- **The human writes first.** I wrote my requirements, assumptions and stack (`docs/brainstorm/user-draft.md`) before any agent touched the design.
- **An orchestrator delegates by complexity:**
  - Antigravity (Gemini) for brainstorming and independent cross-review.
  - Claude Opus for design review, planning, debugging and the final code review.
  - Claude Haiku workers for the implementation phases, which were mechanical once the plan named exact files and tests. Claude Sonnet workers did the fix batches after PR review, and Claude Opus did the reviews and debugging. Two of them ran in parallel worktrees (phase 7a).
  - A Claude docs worker for these documents.
  - Each worker owns a fixed set of files.
- **Written rules instead of chat memory:** `CLAUDE.md`, `.claude/rules/`, the `plan-story`/`implement`/`review-code` skills and `docs/code-standards.md`. `.githooks/` and `./mvnw verify` (Spotless, Checkstyle, tests) enforce them mechanically.
- **Decisions stay with me.** Agents discuss in an append-only log and post anything that is my call as `needs-user`. Every decision is recorded as "Agent selection → Developer decision (after review, date) — confirms/overrides/adds → Reason" (`docs/system-design.md` §10).

### Verifying and refining the AI's output

- **The AI challenged me:** my 500M bookings/month figure was impossible, and "one technician, three cars" broke the requirement (A-002, A-005).
- **Agents checked each other against primary sources:**
  - Claude verified `EXCLUDE` in the PostgreSQL docs and rejected an advisory-lock layer (C-003, C-008). It found that the drafted `IN_FLIGHT` error could never fire (C-017).
  - Gemini found the Hibernate rollback-only pitfall and a partition start-up race (A-022, A-023). Its Jackson suggestion was rejected with a test (C-020).
- **The red team, one finding at a time:** 15 findings, each decided by me (14 accepted, 1 rejected). I asked for root causes first: why exclusion inserts deadlock, and who owns the audit partitions.
- **Deviations caught during implementation:**
  - The implementer picked AOSP formatting; I chose Google style.
  - It dropped Markdown from Spotless; I put it back.
  - It added bay/technician lock ordering to stop a deadlock storm. I reverted it and moved it to the roadmap.
  - An Opus debugger then found the real root cause: a livelock. PostgreSQL breaks one wait cycle per `deadlock_timeout` (median gap 101 ms), and victims retrying at once re-knotted it.
  - The fix is a randomized 1.6–3.2 s backoff inside a 10 s deadline. It went 5/5 green with zero `503`s.
- **Cross-review:** Opus and Gemini checked 77 recorded decisions against the code and reported 70 aligned, 0 deviations, 1 High finding. That summary missed a deviation: the cancel path used a row lock (`PESSIMISTIC_WRITE`). A later line-level review of PR #10 found it, with 9 Approach A and 18 convention findings in total. All are fixed: cancel is one conditional `UPDATE` with no explicit locks. That finding (user input as a metric tag) came from the plan, not the implementer. I made the contract calls: the 10 s deadline, `503 KUSS_SERVICE_BUSY`, and trace ids without per-request log lines.

### Ensuring final quality

- **Correctness lives in the database:** overlap constraints, plus idempotency keys committed with the booking. No application bug or extra instance can double-book a bay, technician or vehicle.
- **Tests on real PostgreSQL:** one test per `KUSS_*` code, plus a concurrency proof (N parallel bookings for K free pairs give exactly K `201`). That proof is the release gate and is never cut.
- **Objective gates and a human gate:** a cheaper model implemented, so the exact plan and `./mvnw verify` carried the quality. A stronger-model cross-review checked it, and I approved before anything was pushed.

