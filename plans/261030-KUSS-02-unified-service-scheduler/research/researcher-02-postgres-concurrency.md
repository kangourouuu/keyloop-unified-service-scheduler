# KUSS-02 Research: PostgreSQL Concurrency for Booking

Scope: implement approved Approach A (partial GiST exclusion + retry) and idempotency-in-same-tx. Decisions fixed; this covers HOW.
Tags: **[doc]** verified in fetched official docs/source; **[src]** PG/Spring source knowledge, not fetched, confirm in integration test.

## 1. Partial exclusion constraints

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;  -- only for uuid "=" (gist_uuid_ops)

ALTER TABLE appointment
  ADD CONSTRAINT appointment_time_order CHECK (end_time > start_time),
  ADD CONSTRAINT excl_appointment_bay_overlap
    EXCLUDE USING gist (service_bay_id WITH =, tstzrange(start_time, end_time, '[)') WITH &&)
    WHERE (status = 'CONFIRMED'),
  ADD CONSTRAINT excl_appointment_tech_overlap
    EXCLUDE USING gist (technician_id WITH =, tstzrange(start_time, end_time, '[)') WITH &&)
    WHERE (status = 'CONFIRMED'),
  ADD CONSTRAINT excl_appointment_vehicle_overlap
    EXCLUDE USING gist (vehicle_id WITH =, tstzrange(start_time, end_time, '[)') WITH &&)
    WHERE (status = 'CONFIRMED');
-- bay/tech/vehicle columns MUST be NOT NULL: NULL never conflicts in an exclusion check.
```
- btree_gist is only for uuid `=`; `&&` on tstzrange is native. `tstzrange(ts,ts,text)` is IMMUTABLE, so legal here.
- **Waiting [doc]:** an inserter meeting an uncommitted conflicting row waits for that tx to end, then re-checks (documented for unique indexes; exclusion uses the same path **[src]**). Winner commits -> 23P01; winner aborts -> insert succeeds.
- After 23P01 the tx is dead; a **new tx** (READ COMMITTED, fresh snapshot per statement) sees the winner **[doc]**.

## 2. Retry: whole tx per attempt, no savepoints

Verdict: **retry the whole transaction; do not use savepoints or `Propagation.NESTED`.**
- `NESTED` = JDBC savepoints. `JpaTransactionManager.nestedTransactionAllowed` defaults `false`; Spring: "JPA itself does not support nested transactions"; savepoints do not revert EntityManager state **[doc]** (javadoc 7.0.10).
- Hibernate keeps the failed entity in the persistence context after rollback-to-savepoint. Avoid: `JdbcClient` on the write path (no entities).
- Whole-tx retry: failed attempt (incl. claim) rolls back fully; next attempt sees the winner. Reads stay on JPA; `JdbcClient` joins the JPA tx's connection **[src]**.

```java
// Attempt = new tx. Do NOT wrap this loop in an outer @Transactional (kills the fresh snapshot).
private static final int MAX_ATTEMPTS = 20; // ponytail: safety cap; loop normally ends when candidates run out

Appointment book(BookCmd cmd) {
    for (int attempt = 1; ; attempt++) {
        try {
            return tx.execute(s -> bookOnce(cmd));  // tx = TransactionTemplate(DataSourceTransactionManager)
        } catch (DataIntegrityViolationException | DeadlockLoserDataAccessException e) {
            String state = pgError(e).map(PSQLException::getSQLState).orElse("");
            String constraint = pgError(e).map(pe -> pe.getServerErrorMessage().getConstraint()).orElse("");
            if ("excl_appointment_vehicle_overlap".equals(constraint))
                throw new VehicleAlreadyBookedException();   // 409 KUSS_VEHICLE_ALREADY_BOOKED, no retry
            boolean retryable = "23P01".equals(state) || "40P01".equals(state); // bay/tech conflict or deadlock victim
            if (!retryable || attempt >= MAX_ATTEMPTS) throw e;
        }
    }
}
```
- Termination: every 23P01 means a winner committed, so the next candidate query excludes that bay/tech. Candidate query must use the **same overlap predicate** as the constraint.

## 3. Extracting the violated constraint

Spring 7 **[doc]** (`sql-error-codes.xml`): `dataIntegrityViolationCodes` = `23000,23502,23503,23514` (no `23P01`); fallback by SQLState class `23` -> `DataIntegrityViolationException` **[src]**. `55P03` -> `CannotAcquireLockException`; `40P01` -> `DeadlockLoserDataAccessException`.

```java
static Optional<PSQLException> pgError(Throwable t) {
    for (Throwable c = t; c != null; c = c.getCause())
        if (c instanceof PSQLException pe) return Optional.of(pe);
    return Optional.empty();
}
// state:      pgError(e).map(PSQLException::getSQLState)
// constraint: pgError(e).map(pe -> pe.getServerErrorMessage().getConstraint())  // structured field, not message text
```

## 4. Claim-first idempotency

Verdict: **claim the key first, in the same tx, before the candidate loop.** Design currently inserts the key last, so same-key requests both do full booking work and collide at the end.

```sql
CREATE TABLE idempotency_key (
  idempotency_key varchar(128) PRIMARY KEY,
  request_hash    char(64)     NOT NULL,
  response_status int,          -- NULL while claimed; set before COMMIT
  response_body   jsonb,
  created_at      timestamptz  NOT NULL DEFAULT now()
);

SET LOCAL lock_timeout = '100ms';
INSERT INTO idempotency_key (idempotency_key, request_hash, created_at)
VALUES (:key, :hash, now())
ON CONFLICT (idempotency_key) DO NOTHING;   -- 1 row = claimed; 0 rows = other tx committed it
-- 0 rows -> SELECT request_hash, response_status, response_body (new statement sees the commit)
-- success -> UPDATE idempotency_key SET response_status=:s, response_body=:b WHERE idempotency_key=:key
```
- Waiter blocks on the uncommitted claimant. Commit -> 0 rows; abort -> insert proceeds **[doc]** (sql-insert: "Tables with unique indexes might block..."). SELECT after 0 rows sees the commit (READ COMMITTED) **[doc]**.
- `ON CONFLICT` has no `NOWAIT`, so `lock_timeout` is the only bound; expect `55P03` **[src]**. Verify: session A claims uncommitted, session B with `SET LOCAL lock_timeout` must get `55P03`. Map `CannotAcquireLockException` -> `409 KUSS_IDEMPOTENCY_IN_FLIGHT`.

## 5. Partitioned `request_audit`

```sql
CREATE TABLE request_audit (
  id              uuid         NOT NULL,
  occurred_at     timestamptz  NOT NULL,       -- renamed from "timestamp"
  request_id      varchar(64)  NOT NULL,
  http_method     varchar(10)  NOT NULL,
  path            varchar(255) NOT NULL,
  http_status     int          NOT NULL,
  error_code      varchar(64),
  duration_ms     int          NOT NULL,
  request_payload jsonb,
  PRIMARY KEY (id, occurred_at)                -- PK must include partition key [doc]
) PARTITION BY RANGE (occurred_at);

CREATE TABLE request_audit_default PARTITION OF request_audit DEFAULT;
CREATE INDEX idx_request_audit_occurred_brin ON request_audit USING brin (occurred_at);
CREATE INDEX idx_request_audit_request_id    ON request_audit (request_id);
```
- No `CREATE INDEX CONCURRENTLY` on partitioned tables **[doc]**: fine, V-migration on empty table. Indexes propagate to new partitions **[src]**.
- Daily partition, UTC bounds: `CREATE TABLE IF NOT EXISTS request_audit_p20261031 PARTITION OF request_audit FOR VALUES FROM ('2026-10-31 00:00:00+00') TO ('2026-11-01 00:00:00+00');`
- `IF NOT EXISTS` skips an existing same-name table with a NOTICE, even if it is not a partition of this parent **[src]**.
- Default overlap: creation fails with `updated partition constraint for default partition "request_audit_default" would be violated by some row` **[src]**. Recovery: move those rows out, retry.
- Retention: `DROP TABLE IF EXISTS request_audit_p20261016;` needs ACCESS EXCLUSIVE on the parent **[doc]**. Set `lock_timeout` in the job and retry.
- **DROP does not prune DEFAULT.** Job must also run `DELETE FROM request_audit_default WHERE occurred_at < now() - interval '14 days';`.
- Daily `@Scheduled(cron = "0 5 0 * * *", zone = "UTC")`: create partitions D+1..D+7, drop older than D-14, delete old default rows; also run creation on startup.

## 6. Availability read index

Verdict: **keep.** GiST exclusion indexes are keyed by bay/tech/vehicle and cannot serve the dealership-wide read; partial predicate must match the query literally.

## 7. Deadlocks and pool sizing

- ~~Booking-vs-booking cycles should not occur~~ **Corrected (Red Team #1, 2026-10-31):** cycles DO occur. PG checks an EXCLUDE constraint after inserting its own index tuple (`execIndexing.c`, "unprincipled deadlock"), so two concurrent conflicting inserts each wait on the other → 40P01 after `deadlock_timeout`. The 40P01 retry covers it; set `deadlock_timeout=100ms`. Cancel-vs-booking is plain waiting.
- **Verdict on 40P01:** yes, retry like 23P01 (PG aborts the victim tx whole; code above does this).
- **Pool starvation:** never take a second connection while holding one (e.g. `REQUIRES_NEW` audit inside a booking tx). Dispatch audit after the business tx closes.
- Hikari default pool = 10. For N=20 test set pool >= 20; assert counts (exactly K `201`), not timings.
## Design fixes for the plan

1. Idempotency: claim-first (section 4); response columns nullable.
2. `request_audit` PK -> `(id, occurred_at)`; `timestamp` -> `occurred_at`.
3. NOT NULL on bay/tech/vehicle; `CHECK (end_time > start_time)`; design 7.2 "rollback sub-tx" -> whole-tx retry.
## Unresolved questions

1. Confirm exclusion-constraint checks wait (not just unique) and the retry sees the winner (2-session integration test).
2. Confirm `lock_timeout` aborts the transactionid wait inside `INSERT ... ON CONFLICT DO NOTHING` (55P03).
3. Confirm default-partition error text and SQLSTATE on the target PG version (docker-compose image: 16 or 17?).
4. `DETACH PARTITION CONCURRENTLY` restrictions with a DEFAULT partition. Plain `DROP` is the default choice.
5. `MAX_ATTEMPTS` and the response when hit (`409 KUSS_NO_CAPACITY` vs `500`). Spring 7 exception types for 23P01/55P03: confirm in test.
## Sources

- PostgreSQL INSERT / ON CONFLICT: https://www.postgresql.org/docs/current/sql-insert.html
- PostgreSQL Partitioning: https://www.postgresql.org/docs/current/ddl-partitioning.html
- PostgreSQL Index Uniqueness Checks: https://www.postgresql.org/docs/current/index-unique-checks.html
- Spring JpaTransactionManager 7.0.10: https://docs.spring.io/spring-framework/docs/7.0.10/javadoc-api/org/springframework/orm/jpa/JpaTransactionManager.html
- Spring sql-error-codes.xml: https://github.com/spring-projects/spring-framework/blob/main/spring-jdbc/src/main/resources/org/springframework/jdbc/support/sql-error-codes.xml
