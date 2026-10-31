# Phase 02 — Schema and seed

## Context Links
- Plan: [plan.md](./plan.md) · Draft §6 (model, constraints) · [researcher-02](./research/researcher-02-postgres-concurrency.md) §1, §4, §5
- C-017 #2, #3; researcher-02 §4, A-022 #2 (nullable response columns)

## Overview
- Priority: P1 · Status: Pending · Effort: 4h · Story: KUSS-04 · Commit: `[KUSS-04] Add Flyway schema, seed data and read entities`
- Flyway V1 schema (all draft §6 tables + constraints), V2 deterministic seed, JPA `*Entity` + `*Repository` for reads.

## Key Insights
- NULL never conflicts in an exclusion check → `service_bay_id`, `technician_id`, `vehicle_id` NOT NULL.
- `btree_gist` only needed for uuid `=`; `tstzrange(...,'[)')` `&&` is native and IMMUTABLE.
- Partitioned table PK must include partition key → `PRIMARY KEY (id, occurred_at)`; column renamed `timestamp` → `occurred_at`.
- Read index must repeat the partial predicate literally (`WHERE status = 'CONFIRMED'`) to be usable.
- `ddl-auto: validate` → use `varchar` (not `char`) for entity-mapped columns, else Hibernate rejects `bpchar`.
- Hibernate default names do not match the schema (e.g. `@ElementCollection` join column defaults to `technician_entity_id`) → every `*Entity` mapping names its table/column explicitly. <!-- Updated: Red Team #2 -->
- Seed shapes the concurrency tests: London OIL_CHANGE = 3 active bays vs 4 techs (bay-bound, K=3); BRAKE_REPAIR = 3 bays vs 2 techs (tech-bound, K=2).
- **Developer decision (after review, 2026-10-31):** JdbcClient in this phase is replaced by JPA (Spring Data @Query / entity persist + flush; EntityManager native queries only for partition DDL) — overrides. Reason: one data-access style.

## Requirements
- Functional: schema per draft §6 + idempotency + partitioned audit; seed ≥2 dealerships in different non-UTC zones, bays (incl. one inactive), techs with distinct skills, service types 30/60/90/120, 3 customers, 24 vehicles; fixed UUIDs.
- Non-functional: versioned Flyway migrations only (no hand-applied DDL); applied automatically on startup. Flyway tracks them in its own `flyway_schema_history` table. Hibernate never changes the schema (`ddl-auto: validate`, `generate-ddl: false`). Files are named `V<version>__<description>.sql` (sequential integer, lower snake_case description starting with a verb). A committed migration is never edited: any change is a new migration. The only DDL outside Flyway is phase 07's `request_audit` day-partition create/drop. <!-- Updated: developer decision 2026-10-31 -->

## Architecture
`V1__create_schema.sql` (core of it):
```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;
-- dealership: CHECK (open_time < close_time) + minutes of open/close IN (0,30) (keeps the grid valid)
-- service_type: code varchar(40) PK, duration_minutes CHECK (duration_minutes > 0 AND duration_minutes % 30 = 0)
CREATE TABLE appointment (
  id uuid PRIMARY KEY,
  dealership_id uuid NOT NULL REFERENCES dealership(id),
  customer_id uuid NOT NULL REFERENCES customer(id),
  vehicle_id uuid NOT NULL REFERENCES vehicle(id),
  service_bay_id uuid NOT NULL REFERENCES service_bay(id),
  technician_id uuid NOT NULL REFERENCES technician(id),
  service_type_code varchar(40) NOT NULL REFERENCES service_type(code),
  start_time timestamptz NOT NULL,
  end_time timestamptz NOT NULL,
  status varchar(16) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  cancelled_at timestamptz,
  CONSTRAINT chk_appointment_time_order CHECK (end_time > start_time),
  CONSTRAINT chk_appointment_status CHECK (status IN ('CONFIRMED', 'CANCELLED')),
  CONSTRAINT chk_appointment_cancelled_at CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL)),
  CONSTRAINT excl_appointment_bay_overlap EXCLUDE USING gist
    (service_bay_id WITH =, tstzrange(start_time, end_time, '[)') WITH &&) WHERE (status = 'CONFIRMED'),
  CONSTRAINT excl_appointment_tech_overlap EXCLUDE USING gist
    (technician_id WITH =, tstzrange(start_time, end_time, '[)') WITH &&) WHERE (status = 'CONFIRMED'),
  CONSTRAINT excl_appointment_vehicle_overlap EXCLUDE USING gist
    (vehicle_id WITH =, tstzrange(start_time, end_time, '[)') WITH &&) WHERE (status = 'CONFIRMED')
);
CREATE INDEX idx_appointment_dealership_window
  ON appointment (dealership_id, start_time, end_time) WHERE status = 'CONFIRMED';

CREATE TABLE idempotency_key (
  idempotency_key varchar(128) PRIMARY KEY,
  request_hash    varchar(64)  NOT NULL,
  response_status int,                       -- NULL only while claim uncommitted
  response_body   jsonb,
  created_at      timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE request_audit (
  id uuid NOT NULL, occurred_at timestamptz NOT NULL, request_id varchar(64) NOT NULL,
  http_method varchar(10) NOT NULL, path varchar(255) NOT NULL, http_status int NOT NULL,
  error_code varchar(64), duration_ms int NOT NULL, request_payload jsonb,
  PRIMARY KEY (id, occurred_at)
) PARTITION BY RANGE (occurred_at);
CREATE TABLE request_audit_default PARTITION OF request_audit DEFAULT;
CREATE INDEX idx_request_audit_occurred_brin ON request_audit USING brin (occurred_at);
CREATE INDEX idx_request_audit_request_id ON request_audit (request_id);
```
Other tables per draft §6: `dealership`, `customer` (email UNIQUE), `vehicle` (vin varchar(17) UNIQUE, customer_id NOT NULL FK), `service_bay` (UNIQUE (dealership_id, bay_code), active), `technician` (active), `technician_skill` (PK (technician_id, service_type_code), both FK).

`V2__insert_seed_data.sql` — UUID scheme `00000000-0000-0000-<TTTT>-<NNNNNNNNNNNN>`, TTTT: 0001 dealership, 0002 bay, 0003 technician, 0004 customer, 0005 vehicle.

| Entity | Rows |
|---|---|
| Dealerships | `…0001-000000000001` London `Europe/London` 08:00–18:00; `…0001-000000000002` New York `America/New_York` 08:00–17:00 |
| Service types | OIL_CHANGE 30, TIRE_SERVICE 60, BRAKE_REPAIR 90, ENGINE_DIAGNOSTIC 120 (all active) |
| London bays | `…0002-000000000101..103` active, `…104` inactive |
| London techs | 101: OIL, TIRE, BRAKE · 102: OIL, TIRE · 103: OIL, BRAKE · 104: OIL · 105: ENGINE only |
| NY bays / techs | bays `…0002-000000000201..202`; tech 201: all four, tech 202: OIL |
| Customers | `…0004-000000000001..003`, synthetic names, `customerN@example.test` |
| Vehicles | 24 via `generate_series`, owner round-robin |

```sql
INSERT INTO vehicle (id, customer_id, vin, license_plate, make, model, model_year)
SELECT ('00000000-0000-0000-0005-' || lpad(n::text, 12, '0'))::uuid,
       ('00000000-0000-0000-0004-' || lpad(((n - 1) % 3 + 1)::text, 12, '0'))::uuid,
       'KLTEST' || lpad(n::text, 11, '0'), 'KL-' || lpad(n::text, 4, '0'), 'Toyota', 'Corolla', 2020
FROM generate_series(1, 24) AS n;
```

Entities (field access, protected no-arg ctor, getters only — writes go through `JdbcClient`): <!-- Superseded: developer decision 2026-10-31, JPA only (see Conventions table) -->
- `DealershipEntity` (`dealership`), `ServiceTypeEntity` (`service_type`, `@Id String code`), `ServiceBayEntity`, `TechnicianEntity` (+ `@ElementCollection @CollectionTable(name = "technician_skill", joinColumns = @JoinColumn(name = "technician_id")) @Column(name = "service_type_code") Set<String> skillCodes`, lazy), `CustomerEntity`, `VehicleEntity` (plain `UUID customerId`, no associations), `AppointmentEntity` (plain UUID FK columns, `@Enumerated(STRING) AppointmentStatus status`, `Instant` times).
- Every entity: `@Table(name = "…")` and `@Column(name = "snake_case")` on every field (no reliance on the naming strategy).
- Every entity: Lombok `@Getter @Setter @Builder @NoArgsConstructor(access = PROTECTED) @AllArgsConstructor(access = PRIVATE)`; never `@Data`, `@EqualsAndHashCode` or `@ToString` (`docs/code-standards.md`). <!-- Updated: conventions 2026-10-31 -->
- No entity for `idempotency_key` / `request_audit`: only `JdbcClient` touches them (YAGNI). <!-- Superseded: developer decision 2026-10-31, JPA only (see Conventions table) -->

## Related Code Files
`{root}` = `/Users/kangourouuu/orca/workspaces/keyloop-unified-service-scheduler/tilefish`. Create:
- `{root}/src/main/resources/db/migration/V1__create_schema.sql`, `{root}/src/main/resources/db/migration/V2__insert_seed_data.sql`
- `{root}/src/main/java/com/keyloop/scheduler/catalog/{DealershipEntity,ServiceTypeEntity,ServiceBayEntity,TechnicianEntity,CustomerEntity,VehicleEntity}.java`
- `{root}/src/main/java/com/keyloop/scheduler/catalog/{DealershipRepository,ServiceTypeRepository,ServiceBayRepository,TechnicianRepository,CustomerRepository,VehicleRepository}.java`
- `{root}/src/main/java/com/keyloop/scheduler/appointment/{AppointmentEntity,AppointmentStatus,AppointmentRepository}.java`
- `{root}/src/test/java/com/keyloop/scheduler/SeedIds.java` (UUID constants for tests)
- `{root}/src/test/java/com/keyloop/scheduler/appointment/SchemaConstraintsIT.java`
Modify:
- `{root}/src/test/java/com/keyloop/scheduler/IntegrationTestSupport.java`: `@BeforeEach` in one `TransactionTemplate` tx: `SET LOCAL lock_timeout = '5s'` then `TRUNCATE appointment, idempotency_key` (a leaked lock fails the test instead of hanging the suite, Red Team #3); helper `insertAppointment(bayId, techId, vehicleId, start, end, status)` (`INSERT … SELECT … FROM vehicle` to derive customer, London dealership by default).

## Implementation Steps
1. Tests first (red): `SchemaConstraintsIT` via `JdbcClient`, constraint name read from `PSQLException#getServerErrorMessage().getConstraint()` (test-local cause-chain helper). Behaviour cases only; Postgres built-ins (NOT NULL, CHECK) are not re-tested <!-- Updated: Red Team #7, #11, #13 -->: <!-- Superseded: developer decision 2026-10-31, JPA only (see Conventions table) -->
   - overlapping bay → `excl_appointment_bay_overlap`; tech → `excl_appointment_tech_overlap`; vehicle → `excl_appointment_vehicle_overlap`
   - back-to-back 09:00–10:00 + 10:00–11:00 on same bay/tech/vehicle → both inserted
   - first row set `CANCELLED` (+ `cancelled_at`) → overlapping CONFIRMED insert succeeds
   - **concurrent (early release-gate signal):** two `DataSource` connections in try-with-resources, `autoCommit=false`, start latch, each thread inserts an overlapping row on the same bay (distinct tech/vehicle) and commits; `future.get(10, SECONDS)` → exactly one commits, the other fails with SQLState `23P01` or `40P01`; one CONFIRMED row remains
   - one `request_audit` row at fixed `2000-01-01T00:00:00Z` → lands in `request_audit_default` (fixed date stays valid after phase 07 creates daily partitions); deleted in `finally`
2. No `SeedDataIT`: seed counts are locked by the burst ITs (exact K), the `skillCodes` mapping is proven by `CatalogApiIT` (phase 04) and `ddl-auto: validate` on startup.
3. Write `V1__create_schema.sql`, `V2__insert_seed_data.sql`. Run `./mvnw verify` → log shows `Successfully applied 2 migrations`.
4. Entities + repositories (plain `JpaRepository<…, UUID>` / `<…, String>`; query methods come in phases 04–06).
5. `./mvnw verify` green.

## Todo List
- [ ] `SchemaConstraintsIT` written first (red), incl. the two-connection concurrent case
- [ ] V1 schema: 8 tables + 3 EXCLUDE + checks + read index + idempotency + partitioned audit
- [ ] V2 seed with fixed UUID scheme
- [ ] 7 entities + `AppointmentStatus` + 7 repositories
- [ ] `IntegrationTestSupport` truncate + `insertAppointment` helper, `SeedIds`
- [ ] `./mvnw verify` green

## Success Criteria
- `./mvnw verify` green; `SchemaConstraintsIT` (7 tests: 3 overlaps, back-to-back, cancelled-frees, concurrent, audit default) passes.
- Hibernate `validate` passes on startup (no `SchemaManagementException`).

## Risk Assessment
- Hibernate type mismatch under `validate` (e.g. `int` vs `Integer`, `time`) → align entity types; never relax to `none` without noting it.
- Seed counts drive phase 05 K values → the burst ITs fail on any change; changing seed must update tests.

## Security Considerations
- Seed data is synthetic (`example.test` emails); no real PII.

## Next Steps
- Phase 03 error handling; phase 04 adds read queries on these repositories.
