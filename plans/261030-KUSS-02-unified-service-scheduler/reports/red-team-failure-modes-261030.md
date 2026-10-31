# Red Team — Failure Mode Analyst (KUSS-02 plan, 2026-10-30)

Scope: plan.md + phase-01..07. Not repeated here because other reports already cover them: pool exhaustion and missing timeouts (security F3), and the shared-table rename in the audit-failure test (scope F8).

## Finding 1: Concurrent bookings deadlock on the same exclusion index, so research §7's "no cycles" claim is wrong
- **Severity:** High
- **Location:** research/researcher-02 §7 "Deadlocks and pool sizing"; Phase 05 "Key Insights" bullet 2 and "Architecture" (`MAX_ATTEMPTS` ponytail comment); A-023 Q3 ("at most min(B, T) attempts")
- **Flaw:** The plan says a cycle "would need opposite orders". That is not true. When two transactions insert conflicting rows at the same time, each one adds its index entry and then checks the index. Each sees the other's uncommitted entry and waits for it. That is a two-way wait on one index. PostgreSQL's `execIndexing.c` documents this for exclusion constraints and calls it an "unprincipled deadlock". Only `ON CONFLICT` speculative insertion avoids it, and exclusion constraints cannot use that.
- **Failure scenario:** In the N=20 burst, ties are broken at random (3 bays, 4 tied techs). Many pairs pick the same bay or the same tech at the same moment, and every such pair deadlocks. Each deadlock stays blocked for `deadlock_timeout` (1 s by default) while both sides hold pooled connections. The victim gets 40P01. That retry removes no candidate, so the bound of "≤ min(B,T) attempts" and the comment "cap only trips on a … bug" do not hold. Correctness survives because 40P01 is retried. What breaks:
  - burst latency and test wall-clock become multiples of 1 s;
  - `gist_retries{conflict_type=deadlock}` dominates the metric;
  - two same-vehicle requests can deadlock on the vehicle index, get counted as a `deadlock` retry, and only report the vehicle conflict on the next attempt.
- **Evidence:** research-02 §7: "Booking-vs-booking cycles should not occur … Reasoned from PG internals, not proven." Phase 05: "Loop ends by design."
- **Suggested fix:**
  - Correct the reasoning in phase 05: 40P01 is the expected path, not an edge case.
  - Keep the retry.
  - In `TestcontainersConfiguration`, start Postgres with `-c deadlock_timeout=100ms` so bursts finish quickly and the deadlock path runs on every build.
  - In `BookingConcurrencyIT`, assert `gist_retries{deadlock} ≥ 0` only. Never assert it is 0.
  - Base `MAX_ATTEMPTS` on "N concurrent requests + K", not on "it should never happen".

## Finding 2: A leaked holder connection or a stuck waiter hangs the whole IT suite at `TRUNCATE`
- **Severity:** High
- **Location:** Phase 02 "Related Code Files" (`IntegrationTestSupport` `@BeforeEach TRUNCATE appointment, idempotency_key`); Phase 05 step 4 (`holdIdempotencyClaim` / `awaitClaimWaiter`); Phase 06 step 1 #6
- **Flaw:** `TRUNCATE` needs an ACCESS EXCLUSIVE lock, and the plan sets no lock or statement timeout in tests. The in-flight helper keeps a raw `autoCommit=false` connection with a row lock on `idempotency_key`. A booking thread or HTTP request blocks behind that lock, and nothing guarantees cleanup.
- **Failure scenario:**
  - Leaked holder: an assertion fails, or Awaitility times out, between `holdIdempotencyClaim` and `commit()`/`rollback()`. The holder connection stays open and keeps its lock. The waiter thread or Tomcat request stays blocked behind it. The next test's `@BeforeEach TRUNCATE` waits forever, so CI hangs with no failure message instead of reporting one red test.
  - Wrong waiter matched: `awaitClaimWaiter()` searches all of `pg_stat_activity` for "a backend waiting on a lock whose query starts `INSERT INTO idempotency_key`". It can match a stale waiter left by an earlier failed test. It can also match nothing when the SQL is a Java text block that starts with a newline or indentation, which turns into a 5 s flake.
- **Evidence:** Phase 05 step 4: "holder `Connection` from `DataSource`, `autoCommit=false` … Awaitility until `pg_stat_activity` shows a backend with `wait_event_type = 'Lock'` and query starting `INSERT INTO idempotency_key`". There is no try/finally, no timeout, and no pid filter.
- **Suggested fix:**
  - Have `holdIdempotencyClaim` return an `AutoCloseable` used in try-with-resources, with rollback and close in `close()`.
  - Run the `TRUNCATE` with `SET lock_timeout = '5s'` (test-only), so a leak fails fast.
  - In `awaitClaimWaiter`, match on `pg_blocking_pids(pid) @> ARRAY[holderPid]`, not on the query text.
  - Bound the waiter futures with `get(10, SECONDS)` and shut down the executor in `finally`.

## Finding 3: A vehicle double-booking is reported as `KUSS_NO_CAPACITY` whenever the slot is otherwise full
- **Severity:** Medium
- **Location:** Phase 05 "Architecture" (`bookOnce`: `pickCandidate` throws NO_CAPACITY before the INSERT); Phase 05 "Requirements" (vehicle overlap → 409 VEHICLE)
- **Flaw:** The vehicle conflict is only detected by the `excl_appointment_vehicle_overlap` violation on INSERT. The INSERT only runs if `pickCandidate` finds a free pair, so a full slot hides the vehicle conflict.
- **Failure scenario:**
  - Sequential, no race needed: vehicle V books London OIL_CHANGE 10:00 on bay 101. Two other vehicles fill bays 102 and 103. V asks again (new key, OIL_CHANGE or BRAKE_REPAIR at 10:00). The candidate list is empty, so the reply is `409 KUSS_NO_CAPACITY`. The client then tries other slots and never learns it already has a booking.
  - Concurrent: same vehicle, last free pair. The loser gets bay/tech 23P01 or 40P01 (Finding 1), retries, finds no candidates and also returns NO_CAPACITY.
  - This conflicts with draft §12 scenario 4. The result depends on unrelated load, and no test covers it: `AppointmentApiIT` #4 runs with free capacity.
- **Evidence:** `CandidateDto candidate = pickCandidate(context); // 409 NO_CAPACITY when a list is empty` comes before `appointmentRepository.insert(...)`.
- **Suggested fix:**
  - In `loadAndValidate`, run one `SELECT EXISTS(… vehicle_id = :v AND status='CONFIRMED' AND tstzrange && …)` and throw VEHICLE_ALREADY_BOOKED. Keep the constraint as the backstop for races.
  - Add a test: fill the slot including V, then V asks again → VEHICLE_ALREADY_BOOKED.

## Finding 4: Response timestamps carry nanoseconds while the DB stores microseconds, so POST/replay and GET disagree (fails on Linux only)
- **Severity:** Medium
- **Location:** Phase 05 "Architecture" (`AppointmentDto.confirmed(context, candidate, clock.instant())`, stored replay body); Phase 06 "Architecture" (`current.cancelled(now)`); Phase 02 (`created_at … DEFAULT now()`)
- **Flaw:** The DTO is built from `clock.instant()` and serialized to the client and into `idempotency_key.response_body`. It is never re-read from the row. On Linux, JDK 15+ gives `Instant.now()` nanosecond precision, but `timestamptz` keeps microseconds. If `created_at` is left to `DEFAULT now()`, it also holds the transaction start time, not the DTO value.
- **Failure scenario:**
  - The POST 201 returns `createdAt …123456789Z`, but GET `/appointments/{id}` returns `…123456Z` (or a different instant).
  - The cancel response's `cancelledAt` differs from the next GET and from a second cancel made with a new key.
  - The "GET by id returns booked row" test passes on macOS (microsecond clock) and fails in a Linux CI or Docker build.
  - Clients that compare replayed and fetched records see drift.
- **Evidence:** Phase 05: `AppointmentDto.confirmed(context, candidate, clock.instant())`. Phase 06: `return current.cancelled(now);`. Nowhere does the plan say which value `created_at` gets or how precision is aligned.
- **Suggested fix:** Use `clock.instant().truncatedTo(ChronoUnit.MICROS)` once per attempt and bind it explicitly to `created_at` and `cancelled_at`. Or use `INSERT … RETURNING` / `UPDATE … RETURNING` and build the DTO from the returned row.

## Finding 5: The phase 02 "audit row lands in DEFAULT" test breaks once phase 07 creates daily partitions in the shared context
- **Severity:** Medium
- **Location:** Phase 02 step 1, last bullet; Phase 07 "Requirements" (startup D..D+7) and step 2 (`PartitionMaintenanceIT`: "`request_audit_default` holds no row for today")
- **Flaw:** The plan does not fix `occurred_at` for the phase 02 insert. Phase 07 changes what that insert does without listing phase 02 tests under "Modify". Both tests share one database.
- **Failure scenario:**
  - If `SchemaConstraintsIT` inserts with `now()`, it passes in phase 02 because no partitions exist yet. From phase 07 on, the row lands in `request_audit_p<today>`, so `SchemaConstraintsIT` fails.
  - If it is "fixed" by inserting a date inside D..D+7, it fails the same way.
  - If it inserts a past date that equals D−20 or D−14, `PartitionMaintenanceIT`'s manual `CREATE TABLE request_audit_p<D-20> PARTITION OF …` fails, because DEFAULT already holds rows for that range. Whether that happens depends on class order.
- **Evidence:** Phase 02: "insert one `request_audit` row with no daily partition → lands in `request_audit_default`". Phase 07 "Modify" lists no phase 02 test.
- **Suggested fix:** In phase 02, insert at a fixed far-past instant (for example `2000-01-01Z`), or drop that assertion (scope F9 already questions it). List the dependency in phase 07.

## Finding 6: Meters are registered lazily, so the Prometheus checks are timing- and order-dependent
- **Severity:** Medium
- **Location:** Phase 07 step 3 (`MetricsIT`: `/actuator/prometheus` contains `scheduler_audit_writes_total`); Phase 07 "Success Criteria" (`grep scheduler_` lists booking, retry, availability and audit meters); Phase 05 step 3 (`VehicleOverlapIT` "gist_retries total unchanged")
- **Flaw:** A Micrometer counter appears in the scrape output only after its first `registry.counter(...)` call.
  - `scheduler.audit.writes` is incremented on the async audit thread.
  - `scheduler.booking.gist_retries` is incremented only after a real conflict.
- **Failure scenario:**
  - `MetricsIT` scrapes right after its HTTP calls. The audit task has not run yet, so `scheduler_audit_writes_total` is missing. This is flaky, and it passes only when an earlier test class already wrote audits.
  - On a fresh `docker compose up`, the success-criteria `curl | grep scheduler_` never shows the retry meter until a conflict happens, so the criterion cannot be met as written.
  - `VehicleOverlapIT` reading `registry.find("…gist_retries").counter().count()` throws an NPE when no retry has happened yet in the context, depending on order.
- **Evidence:** Phase 07 step 3 has no Awaitility on the scrape. Phase 05 step 3 asserts "total unchanged" with no reading defined for "absent".
- **Suggested fix:** Register every meter and tag value at startup: `Counter.builder(...).register(...)` in the owning bean's constructor (audit success/failure, retry bay/technician/deadlock). Read totals with `registry.find(name).counters().stream().mapToDouble(Counter::count).sum()`.

## Finding 7: The compose healthcheck can pass while Postgres is still in its init phase, so the app crashes on the reviewer's first `docker compose up`
- **Severity:** Medium
- **Location:** Phase 01 step 10 (`docker-compose.yml`: "`pg_isready` healthcheck", `depends_on: condition: service_healthy`) and "Success Criteria"
- **Flaw:** On a fresh volume, the official `postgres` entrypoint first runs a temporary server that listens only on the Unix socket. That server creates `POSTGRES_DB`/`POSTGRES_USER`, then the entrypoint restarts the server with TCP enabled. A bare `pg_isready`, with no `-h` and no `-U`/`-d`, uses the socket and reports ready during that window. The plan also gives `app` no `restart:` policy.
- **Failure scenario:** On the first `docker compose up --build` after `down -v`, with a short healthcheck interval, `db` turns healthy against the temporary server. `app` connects over TCP to `db:5432` during the restart, gets connection refused, Flyway or Hikari startup fails, and the container exits. The reviewer's one-command run fails, which is the plan's stated reason for choosing a Dockerfile.
- **Evidence:** "`db` = `postgres:17` (env …, `pg_isready` healthcheck, port 5432)". No healthcheck command or flags and no restart policy are given.
- **Suggested fix:** Use `test: ["CMD-SHELL", "pg_isready -h localhost -U kuss -d kuss"]`, which forces TCP so it is false during init. Add `restart: on-failure` to `app`. Run the phase 01 smoke step from `down -v` (fresh volume).
