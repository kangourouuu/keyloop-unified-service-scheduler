# Video Outline (5–10 min)

Talking points, not a script. They follow the five bullets in the PDF. Target length is about 8 minutes. Show diagrams from `docs/visualization/kuss-design.html` and `docs/system-design.md`. The demo commands are the README's "API walkthrough (curl)".

## 1. Intro: me and the scenario (~0:30)

- Who I am and my role (say it in your own words).
- Scenario A, the Unified Service Scheduler. Backend track: a REST API on PostgreSQL, with Swagger UI and cURL as the client stub.
- The problem in one line: book only when a bay **and** a qualified technician are free for the **whole** duration, even when requests race.

## 2. System design and implementation highlights (~3:00)

- **Architecture** (explainer "Architecture"):
  - One stateless Spring Boot 4.0.9 / Java 21 service on PostgreSQL 17.
  - Packages split by role per domain: catalog, availability, appointment, audit, common.
  - Roadmap items appear dashed: Redis, lock ordering, auth and signing, `pg_partman`.
- **Why so lean:** ~60k bookings/day doesn't justify a queue, a cache or WebSocket. PostgreSQL also holds the idempotency keys and the audit log.
- **The key idea:** three partial `EXCLUDE USING gist` constraints with `[)` ranges on bay, technician and vehicle. The database makes a double booking impossible.
- **The booking path** (§4.2 sequence diagram):
  1. Claim the `Idempotency-Key`.
  2. Vehicle precheck.
  3. Find free pairs, persist + flush, commit, in one transaction per attempt.
  4. Bay/tech `23P01` retries at once.
- **The surprise worth 30 seconds:** concurrent exclusion inserts deadlock by design.
  - The first fix (locks) was reverted.
  - The real cause was a livelock: PostgreSQL breaks one cycle per 100 ms, and victims re-knotted it.
  - The fix: a 1.6–3.2 s randomized backoff inside a 10 s deadline. Overload returns `503 KUSS_SERVICE_BUSY` with `Retry-After`.
- **One error catalog:** `KUSS_*` codes in ProblemDetail, the metrics and `request_audit`, joined by `X-Request-Id`.
- **Audit:** a bounded executor writes outside the booking transaction into daily partitions, created before the server listens. Flyway owns all schema except those partitions.
- **Tests:** 36 unit and 76 integration test methods on real PostgreSQL. The release gate is `BookingConcurrencyIT`: 20 parallel requests for K free pairs give exactly K `201`.
- **Honest limits:** no auth, so anyone can read and cancel, and there is no rate limiting. Both are roadmap.

## 3. AI collaboration story (1–2 min)

- **Setup:**
  - I wrote the draft first.
  - An orchestrator delegates by complexity: Gemini brainstorms and cross-reviews, Claude Opus reviews and debugs, Claude Haiku workers implement (two in parallel for phase 7a).
  - Every decision is recorded as agent selection → my decision → reason.
- **AI challenged me:** impossible traffic sizing, and "one technician, three cars".
- **Agents checked each other:**
  - Claude found the unreachable `IN_FLIGHT` path.
  - Gemini found the Hibernate pitfall and a partition race.
  - Opus + Gemini checked 77 decisions against the code: 70 aligned, 0 deviations, 1 High finding (from the plan).
- **I overrode and corrected:**
  - Idempotency-Key back in, audit and error catalog added, 15 red-team findings decided one by one.
  - Podman, not Docker.
  - Google style, not AOSP.
  - Markdown kept in the gate.
  - Lock ordering reverted; asked for the root cause.
  - The 10 s deadline and the 503 contract.
- **Gates:**
  - `./mvnw verify` (Spotless, Checkstyle, tests) on every phase;
  - a cross-review after implementation;
  - squash-merged PRs per ticket;
  - nothing pushed without my approval.

## 4. Demo (~2:00)

Run from the repo root, using the README walkthrough variables (`BASE`, `LONDON`, `VEHICLE`, `DATE`, `KEY`, `BODY`):

1. `docker compose up --build` (or `podman compose up --build`), then `curl -s localhost:8080/actuator/health`.
2. Open <http://localhost:8080/swagger-ui.html> and show the endpoints and the `Idempotency-Key` header.
3. Catalog: `curl -s $BASE/api/v1/dealerships | jq`, then `curl -s $BASE/api/v1/service-types | jq`.
4. Availability: `curl -s "$BASE/api/v1/dealerships/$LONDON/availability?date=$DATE&serviceType=OIL_CHANGE" | jq '.slots[:3]'`. Point out the local offset and `availableCapacity`.
5. Book: README step 3, giving `201` and the assigned bay and technician. Re-run step 4 of the walkthrough: the replay returns the same id.
6. The errors:
   - step 5, same key with a changed time: `422 KUSS_IDEMPOTENCY_KEY_MISMATCH`;
   - step 6, a new key for the same vehicle: `409 KUSS_VEHICLE_ALREADY_BOOKED`;
   - step 7, no key: `400 KUSS_IDEMPOTENCY_KEY_MISSING`.
7. Cancel: README step 9, giving `200` and `CANCELLED`. Re-run availability: the capacity is back.
8. Metrics: `curl -s localhost:8080/actuator/prometheus | grep scheduler_` (booking results, retries by `conflict_type`, audit writes).
9. The concurrency proof (separate terminal, Docker or Podman running): `./mvnw verify`. Point at `BookingConcurrencyIT` in the output: exactly K bookings out of 20.
10. `docker compose down -v`.

## 5. Learnings and challenges (~1:00)

- Right-sizing the problem removed three pieces of infrastructure.
- Range constraints solve partial overlaps, but bring their own deadlock behaviour. Only a "why?" found the livelock, and adding locks would have hidden it.
- One transaction per attempt makes retries clean, even with JPA-only writes.
- Time zones: one `candidateStarts` function for reads and writes, in local time.
- "Less code" isn't always right: the agents' idempotency shortcut was correct for duplicates but wrong for client retries.
- Multi-agent work needs written protocols, file ownership, decision records and a human gate. AI "done" claims, and AI assumptions about my machine, still need checking.

