# Red Team — Security Adversary (261030)

Plan: `plans/261030-KUSS-02-unified-service-scheduler` · Scope: plan documents only · Fixed decisions (draft §12, no auth) respected; findings flag risks inside them.

## Finding 1: Audit scope test uses raw URI, so writes can skip the audit
- **Severity:** High
- **Location:** Phase 07, section "Architecture" (`RequestAuditFilter`), plus "Requirements" (path ≤ 255)
- **Flaw:** `isAudited = request.getRequestURI().startsWith("/api/")` runs on the raw, undecoded URI, which still holds path parameters. Spring MVC routes on decoded segments with matrix parameters removed (`PathContainer.valueToMatch()`). The filter and the router can therefore see two different paths. The payload whitelist ("only for `POST /api/v1/appointments`") uses the same raw-URI comparison. Separately, `path varchar(255)` and `http_method varchar(10)` have no truncation rule, so an oversized value fails the whole audit INSERT.
- **Failure scenario:** (a) `POST /%61pi/v1/appointments` or `POST /api;x=1/v1/appointments` reaches `AppointmentController`, returns 201 and leaves no audit row. (b) `POST /api/v1/appointments;pad=<300 chars>` is routed normally and books, but the audit INSERT fails on `varchar(255)`. The only trace is `scheduler.audit.writes{status=failure}` +1. Anonymous cancels can be hidden the same way.
- **Evidence:** "`boolean isAudited = request.getRequestURI().startsWith("/api/");`"; "path (URI, ≤ 255)". No test sends an encoded or matrix-param URI.
- **Suggested fix:** Flip the predicate: audit everything except `request.getServletPath().startsWith("/actuator")` (servlet path is decoded and normalized). Store `left(servletPath, 255)` and `left(method, 10)`. Add one `RequestAuditIT` case: `/api;x=1/v1/appointments` produces an audit row with a payload.

## Finding 2: The plan's security claims are false; anyone can list every appointment and cancel it
- **Severity:** High
- **Location:** Phase 06, "Security Considerations"; Phase 04, "Requirements" (catalog) and "Security Considerations"
- **Flaw:** Phase 06 says "IDs are UUIDs (non-enumerable)" and "Responses carry no customer PII beyond IDs". Both are wrong. `GET /api/v1/dealerships` gives every dealership id. `GET /api/v1/dealerships/{id}/appointments?date=` gives every appointment id for that day. `GET /api/v1/customers` returns every customer's name plus their vehicles (VIN and licence plate are personal data under GDPR). No-auth is an accepted decision, but the reviewer-facing docs claim protections that do not exist.
- **Failure scenario:** An anonymous script loops over dealerships × the next 30 dates, lists the appointments and sends `POST /appointments/{id}/cancel` with a fresh `Idempotency-Key` for each one. Every future booking is cancelled within seconds. The same caller downloads names, VINs and plates for all customers in one GET.
- **Evidence:** Phase 06: "IDs are UUIDs (non-enumerable)… Responses carry no customer PII beyond IDs." Phase 04: "`GET /api/v1/customers` (with vehicles; no email/phone)".
- **Suggested fix:** Replace the claim with the true statement: "No auth means full read/cancel by anyone. Roadmap: OAuth2 + per-dealership scoping." Drop `vin` and `licensePlate` from `CustomerDto` (a vehicle id plus make/model is enough to book). Mark `/customers` with a `ponytail:` note about the PII ceiling.

## Finding 3: No transaction or statement timeout, uncapped virtual threads and a default pool of 10 can exhaust DB connections
- **Severity:** High
- **Location:** Phase 05, "Key Insights" (no `lock_timeout`) and "Architecture" (`MAX_ATTEMPTS = 100`); Phase 01, step 8 (`application.yml`); Phase 07, "Requirements" (audit executor)
- **Flaw:** Production gets the Hikari default of 10 connections (only `application-test.yml` raises it to 30). The audit pool permanently uses up to 2 of them. With virtual threads, Tomcat has no request-concurrency cap. No `TransactionTemplate.setTimeout`, `statement_timeout` or Hikari `connectionTimeout` is set. The claim INSERT and EXCLUDE waits are unbounded by design, and each booking can run up to 100 full transactions.
- **Failure scenario:** 200 concurrent booking requests for one popular slot (or 50 requests sharing one `Idempotency-Key`, which all block on the claim INSERT) hold 8 connections. Every other request waits 30 s for a connection, then fails with `CannotGetJdbcConnectionException`. `handleConflict` rethrows it, so each one becomes a 500 with a full `log.error` stack trace. Availability reads and cancels go down with it, and the log volume grows with the attack. `BookingConcurrencyIT` runs with pool 30, so it never shows the production behaviour.
- **Evidence:** "`application-test.yml`: `spring.datasource.hikari.maximum-pool-size: 30`". The prod yml has no pool or timeout keys. "**No `SET LOCAL lock_timeout`**".
- **Suggested fix:** Set `transactionTemplate.setTimeout(5)`. Spring applies it as the JDBC query timeout for `JdbcClient`, so no wait is unbounded; this is a different knob from `lock_timeout`, and 57014 maps to 500/503. Set the prod `maximum-pool-size` and a short `connection-timeout` (e.g. 3 s) explicitly in `application.yml`. Log pool-timeout 500s at WARN without the stack trace.

## Finding 4: No date horizon, so out-of-range dates cause 500s and attackers can fill the calendar
- **Severity:** Medium
- **Location:** Phase 04, "Requirements" and "Architecture" (`TimeSlotUtils.candidateStarts`); Phase 05, "Requirements" (400 rules)
- **Flaw:** Dates are validated only for "past", "off-grid" and "outside hours". There is no upper bound. `LocalDate` accepts `+999999999-12-31`, and PostgreSQL `timestamptz` stops at year 294276.
- **Failure scenario:** (a) `GET …/availability?date=+300000-01-01` or a booking with `desiredStartTime: "+300000-01-01T09:00Z"` fails when the timestamp is bound (22008) or in `plusMinutes`. The catch-all returns a 500 and writes an ERROR stack trace, so each request is a cheap way to spam logs. (b) Vehicle overlap only blocks overlapping times. 24 seeded vehicles can each book one appointment per slot across every future date, so a script can occupy all capacity for years ahead.
- **Evidence:** Phase 05: "off-grid / past / outside hours → 400". No maximum date anywhere in the plan.
- **Suggested fix:** One check in `TimeSlotUtils`: date ≤ today + 90 days, otherwise 400 `KUSS_VALIDATION_FAILED`, used by both availability and booking. Add one row each to `TimeSlotUtilsTest` and `AppointmentApiIT`.

## Finding 5: Flooding reads evicts write audits, and dropped records leave no trace
- **Severity:** Medium
- **Location:** Phase 07, "Requirements" (executor 2/2/1000, rejection → counter + WARN) and "Risk Assessment" ("Audit executor saturation drops records")
- **Flaw:** Every `/api/**` request, including cheap GETs and 404s, shares one 2-thread executor with a 1000-slot queue. When the queue is full, the record is dropped; only a counter increments and a generic WARN is logged. The plan calls this "intentional back-pressure". From an attacker's side, it is a switch that turns off the audit.
- **Failure scenario:** The attacker floods `GET /api/v1/service-types` until the queue fills, then sends the bookings and cancels. Those audit records are rejected. The counter shows that something was lost but not which requests, so the bulk-cancel in Finding 2 cannot be traced afterwards.
- **Evidence:** "rejection → `scheduler.audit.writes{status=failure}` + WARN"; "Audit executor saturation drops records (counted) — intentional back-pressure".
- **Suggested fix:** In the rejection handler, log the full `RequestAuditDto` (requestId, method, path, status, error_code; all non-PII) at WARN, so the log becomes the fallback audit trail. Optionally, do not audit 2xx GETs when the queue is more than 80% full. Writes always win.

## Finding 6: Error details for 400 and passthrough errors are not fixed, so class names and input can leak
- **Severity:** Medium
- **Location:** Phase 03, "Requirements" (mapping table) and "Implementation Steps" step 1
- **Flaw:** Only the 500 row has a fixed detail. The table does not say what detail is sent for `HttpMessageNotReadableException`, `TypeMismatchException` or "other `ErrorResponse`". The obvious way to implement it, `ex.getMessage()` or Spring's default detail, puts Jackson 3 text into the response: `Cannot deserialize value of type 'java.time.OffsetDateTime' from String "…"`, FQCNs such as `com.keyloop.scheduler.appointment.BookAppointmentDto`, and parser offsets. The sanitization test covers only the 500 case.
- **Failure scenario:** `POST /appointments` with `{"desiredStartTime":"x"}` returns a 400 whose detail shows the internal package layout and the DTO type. Phase 03's own non-functional rule ("no … class name … in `detail`") is broken and no test catches it.
- **Evidence:** "`HttpMessageNotReadableException`, `TypeMismatchException` | 400 | `KUSS_VALIDATION_FAILED`" with no detail column. The sanitization assertion appears only on `IllegalStateException("SELECT * FROM secret")`.
- **Suggested fix:** Use fixed details: `"Malformed request body."` and `"Invalid value for parameter '<name>'."` (name only, never the value). Add a `doesNotContain("com.keyloop", "tools.jackson", "java.")` assertion to the `{bad json` and `?n=abc` cases.

## Finding 7: Postgres is reachable from the network with known superuser credentials, and the app connects as that superuser
- **Severity:** Medium
- **Location:** Phase 01, step 10 (`docker-compose.yml`), step 8 (datasource), "Security Considerations"
- **Flaw:** `db` publishes "port 5432", which compose binds to `0.0.0.0`. The credentials `kuss/kuss` are committed to the repo. `POSTGRES_USER` is a superuser, and the app and Flyway both use it at runtime. Least privilege is not considered.
- **Failure scenario:** Anyone on the same Wi-Fi or LAN as a reviewer running `docker compose up` connects with `psql -h <ip> -U kuss` and has superuser rights: they can read or alter all data, and `COPY … TO PROGRAM` gives command execution in the container. If an injection bug ever appears in the app (dynamic DDL in Phase 07 is the most likely place), it also runs as superuser.
- **Evidence:** "`db` = `postgres:17` (env `POSTGRES_DB/USER/PASSWORD=kuss`, …, port 5432)".
- **Suggested fix:** Use `ports: ["127.0.0.1:5432:5432"]`, or drop the mapping since the app uses the compose network. A separate runtime role is a known roadmap ceiling; record it as a `ponytail:` note in compose.

## Finding 8: Actuator Prometheus and Swagger UI are public on the application port in the prod profile
- **Severity:** Medium
- **Location:** Phase 07, "Requirements" (Actuator: expose `health,info,prometheus`); Phase 06, "Requirements" (OpenAPI)
- **Flaw:** `/actuator/prometheus` is served unauthenticated on port 8080 next to the API, in every profile. It exposes per-error-code booking rates, retry counts, Hikari pool usage, JVM and OS details, and library versions. It also lets an attacker watch the pool exhaustion from Finding 3 and the audit drops from Finding 5 as they happen.
- **Failure scenario:** An attacker polls `hikaricp_connections_pending` and `scheduler_audit_writes_total{status="failure"}` to tune the flood until the pool is pinned and the audit is blind.
- **Evidence:** "Actuator: expose `health,info,prometheus`; probes on". No `management.server.port` is set.
- **Suggested fix:** One line: `management.server.port: 8081` in the `prod` profile, and do not publish 8081 in compose. Probes still work in-cluster. Keep Swagger UI (the challenge requires it) but note it in the docs as dev-only.

## Finding 9: Unverified Maven download, floating image tags and a root container
- **Severity:** Medium
- **Location:** Phase 01, steps 1, 2 and 9 ("Implementation Steps"); "Key Insights" (Checkstyle "latest … at implementation time")
- **Flaw:** The wrapper comes from start.spring.io without `distributionSha256Sum`, so the Maven distribution it downloads is never checksum-verified. The Dockerfile uses floating tags `eclipse-temurin:21-jdk`, `21-jre` and `postgres:17` with no digest. The runtime stage has no `USER`, so the JVM runs as root. The Checkstyle version is chosen by whoever implements it.
- **Failure scenario:** A tampered or re-tagged base image or Maven distribution is pulled silently on the next build. Any RCE in the JVM (deserialization or dependency CVE) runs as root inside the container.
- **Evidence:** "`ENTRYPOINT ["java","-jar","/app/app.jar"]`" with no `USER`; "pin a current Checkstyle core (latest 10.x/11.x … at implementation time)".
- **Suggested fix:** Add `distributionSha256Sum` to `maven-wrapper.properties`. Add `USER 10001` (or temurin's non-root user) in stage 2. Pin minor tags (e.g. `21.0.x-jre`, `17.x`) and record the exact Checkstyle version in the plan.

## Finding 10: Unlimited anonymous writes to `idempotency_key` and `request_audit` can fill the disk
- **Severity:** Medium
- **Location:** Phase 06, "Key Insights" (already `CANCELLED` → 200); Phase 07, "Requirements" (audit every `/api/**`, 14-day retention); Phase 06, "Requirements" (`Idempotency-Key` only `@NotBlank @Size(max = 128)`)
- **Flaw:** Cancelling an appointment that is already `CANCELLED` is a success path, so every fresh key commits a new `idempotency_key` row with a jsonb body, kept for 24 h. Every request, including 404s, adds an audit row kept for 14 days. There is no rate limit, no request-body size limit and no character set for the key, so 128 arbitrary Unicode characters are stored per row.
- **Failure scenario:** A loop sends `POST /appointments/{cancelledId}/cancel` with a new random key each time. Each request commits a ~1 KB row plus an audit row. Running for a day fills the Docker volume, and then PostgreSQL rejects every booking write. Only the 00:05 UTC job deletes anything, and only rows older than 24 h or 14 days.
- **Evidence:** "already `CANCELLED` → 200 current state"; "`Idempotency-Key`: `@NotBlank @Size(max = 128)`"; "Audit row per `/api/**` request".
- **Suggested fix:** Return the current state for an already-cancelled appointment without claiming or storing the key: check the status before the claim, since it is a read-only no-op. Restrict the key to `@Pattern("[A-Za-z0-9_-]{1,128}")`. Record "no rate limiting" as an explicit roadmap ceiling in the plan's Out of scope section.
