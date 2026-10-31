---
name: senior-qa
description: Use when deriving test cases for a KUSS story, phase or bug fix in the Keyloop scheduler (Java 21 / Spring Boot 4 / PostgreSQL) — booking, availability, cancel, idempotency, KUSS_* error codes, request audit, concurrency.
---

# Senior QA — Test Case Generation

Turn acceptance criteria into a traceable case list, then into JUnit 5 tests that fail if the behavior breaks. Scope is generating cases and test code only: the `tester` agent runs the suite, the implementer fixes production code.

## Inputs

- Phase file / `plan.md`: Requirements and Success Criteria are the acceptance criteria (AC)
- `docs/system-design.md` §4 (data flow, API surface, error catalog): the contract
- `docs/brainstorm/first-draft-high-level.md` §12 "Mandatory Test Scenarios" 1–11 (M-1..M-11): must stay covered by the suite
- The target source (Glob/Grep/Read)

An AC that is ambiguous or contradicts the design goes under Open Questions. Never invent the rule.

## Output 1 — Case list

`{plan-dir}/reports/senior-qa-{YYMMDD-HHmm}-{slug}.md` (no plan → `plans/reports/`):

| ID | Source | Layer | Given | When | Then | Test |
|----|--------|-------|-------|------|------|------|
| TC-01 | AC-2, M-5 | IT | 1 bay + 1 qualified tech, both booked 09:00–10:00 | POST /appointments at 09:30 | 409, `code=KUSS_NO_CAPACITY`, no new row | `AppointmentBookingIT#rejectsWhenNoFreePair` |

One row → one test method. End with: tests expected to fail until implemented, Open Questions.

## Output 2 — JUnit code

| Layer | Use for | Runner |
|-------|---------|--------|
| `*Test` | Pure logic: grid/hours checks, availability sweep, candidate pick, request hashing, DTO mapping | Surefire, `./mvnw test`, no Spring, no Docker |
| `*IT` | SQL, constraints, transactions, HTTP status + `ProblemDetail`, idempotency, audit | Failsafe, `./mvnw verify`, Spring context + Testcontainers Postgres |

- JUnit 5 + AssertJ (from `spring-boot-starter-test`); `@DisplayName("TC-01 ...")` links code to the list
- Real Postgres via Testcontainers. No H2 (no `EXCLUDE`/gist), no mocked repositories or JDBC to fake a constraint or `23P01`
- Each test seeds its own dealership/bays/technicians/vehicles; never rely on Flyway seed rows that other tests mutate or on test order
- Dates relative to now in the dealership zone (`LocalDate.now(zone).plusDays(n)`), never a hard-coded calendar date
- Error cases assert the whole contract: status, `code`, `requestId` present, no stack trace or SQL in `detail`
- Assert DB state, not only the response: row count, `status`, no overlap
- Only `src/test/**`. Compile before handing off (`./mvnw -q test-compile` or `./gradlew compileTestJava`). Tests for not-yet-built behavior may fail — list them; never `@Disabled`

## Case Checklist

Walk this for every endpoint in scope; keep only what applies:

- **Happy path**: 201/200, body, DB row
- **Each error code** the endpoint can return (API surface table)
- **Time boundaries**: `:00`/`:30` ok vs `:15` → 400; start at opening ok, end past closing → 400; past start → 400; back-to-back 09:00–10:00 + 10:00–11:00 both 201
- **Resources**: bay free but no qualified tech → 409; tech free but no bay → 409; bay/tech of another dealership never picked; inactive bay/tech ignored; cancelled appointment frees its slot
- **Idempotency**: missing key → 400; same key + payload → same status/body, one row; changed payload → 422; same new key concurrently → 409 `KUSS_IDEMPOTENCY_IN_FLIGHT`; 409 `KUSS_NO_CAPACITY` leaves no key, retry runs fresh
- **Concurrency (M-11)**: N parallel, K free pairs → exactly K × 201, N−K × 409 `KUSS_NO_CAPACITY`, K `CONFIRMED` rows, no overlap per bay/tech
- **Cancel**: unknown id → 404; started → 409 `KUSS_APPOINTMENT_ALREADY_STARTED`; already cancelled → 200
- **Audit**: one `request_audit` row per request, including rolled-back 409/500; payload IDs and times only. The write is async — poll with a bounded wait, no fixed sleep

## Concurrency Test Shape

```java
int n = 10, k = 3;                                // k = free (bay, tech) pairs seeded for the slot
CountDownLatch startGate = new CountDownLatch(1); // release all requests at once
try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
    List<Future<Integer>> calls = IntStream.range(0, n)
            .mapToObj(i -> pool.submit(() -> { startGate.await(); return postBooking(vehicleIds.get(i)); }))
            .toList();
    startGate.countDown();
    List<Integer> statuses = new ArrayList<>();
    for (Future<Integer> call : calls) statuses.add(call.get(30, TimeUnit.SECONDS));
    assertThat(statuses).filteredOn(s -> s == 201).hasSize(k);
    assertThat(statuses).filteredOn(s -> s == 409).hasSize(n - k);
}
```

Distinct vehicle and `Idempotency-Key` per call, so only bay/technician contention is under test. Then assert K `CONFIRMED` rows in the DB.

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| "Concurrency" test sends requests one by one | Latch start gate + virtual threads |
| Asserting status only | Also assert `code` and DB state |
| Mocking a repository to simulate `23P01` | Hit the real constraint via Testcontainers |
| Fixtures shared across tests → flaky | Per-test fixtures |
| Changing `src/main` so a test passes | Report it; the implementer fixes it |
