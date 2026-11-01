# Code Standards

Java 21 + Spring Boot 4 conventions for the Unified Service Scheduler. Agents load the short checklist in `.claude/rules/java-conventions.md`; this file is the human-readable version with examples.

These conventions override ponytail's "no interface with a single implementation" rule for `*Service` / `*ServiceImpl`, its "no new dependency" rule for Lombok and MapStruct, and its `ponytail:` marker comments (see §3). Ponytail (minimal code, no speculative abstraction) still applies everywhere else.

## 1. Naming: class suffix → role

|      Suffix      |                                       Role                                       |
|------------------|----------------------------------------------------------------------------------|
| `*Controller`    | Request entry point (REST controller)                                            |
| `*Service`       | Service interface                                                                |
| `*ServiceImpl`   | Implementation of that interface                                                 |
| `*Repository`    | Spring Data JPA repository                                                       |
| `*Entity`        | JPA entity, lower snake_case table name                                          |
| `*Dto`           | Java `record` with `@Builder`, no mapping code                                   |
| `*Mapper`        | MapStruct interface, owns Entity ↔ Dto mapping                                   |
| `*GlobalHandler` | The single global exception handler                                              |
| `*Helper`        | Spring `@Component` with collaborators, no business API, not a service interface |
| `*Utils`         | Static helpers                                                                   |
| `*Constants`     | Shared constants in `common.constants`                                           |
| `*Test`          | Unit test (Surefire)                                                             |
| `*IT`            | Integration test with Testcontainers (Failsafe)                                  |

### Controller: request entry point

```java
@RestController
@RequestMapping("/api/v1/appointments")
class AppointmentController {
  private final AppointmentService appointmentService;
  // constructor injection, delegates to the service, no business logic here
}
```

### Service / ServiceImpl: interface + implementation

```java
public interface AppointmentService {
  AppointmentDto book(BookAppointmentDto request, String idempotencyKey);
}

@Service
class AppointmentServiceImpl implements AppointmentService { ... }
```

### Repository: Spring Data JPA

```java
interface ServiceBayRepository extends JpaRepository<ServiceBayEntity, UUID> {
  List<ServiceBayEntity> findByDealershipIdAndIsActiveTrue(UUID dealershipId);
}
```

The boolean field is `isActive` (column `active`). Renaming it changed the JSON property of bays and technicians from `active` to `isActive`: a **breaking API change**, recorded in the Developer decisions log.

### Entity: lower snake_case table names, Lombok `@Getter @Setter`, never `@Data`

```java
@Entity
@Table(name = "service_bay")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
class ServiceBayEntity { ... }
```

`@Data` (and `@EqualsAndHashCode` / `@ToString`) on an entity builds equals, hashCode and toString from every field, which triggers lazy loads and loops on bidirectional relations. The protected no-args constructor is for JPA; the private all-args one is for `@Builder`. Other table names: `technician_skill`, `request_audit`.

### Dto: always a `record` with `@Builder`, no mapping code

```java
@Builder
public record AppointmentDto(UUID id, UUID vehicleId, Instant startTime, String status) {}
```

Never `class AppointmentDto`, and no `from(...)` / `toEntity()` inside the record: mapping belongs to a `*Mapper`.

### Mapper: MapStruct, owns Entity ↔ Dto mapping

```java
@Mapper(componentModel = "spring")
interface AppointmentMapper {
  AppointmentDto toDto(AppointmentEntity entity);
  AppointmentEntity toEntity(BookAppointmentDto request);
}
```

MapStruct generates the implementation at compile time. The pom adds `lombok-mapstruct-binding` so Lombok runs first and MapStruct can see the generated getters and builders.

### GlobalHandler: one `@RestControllerAdvice`

```java
@RestControllerAdvice
class SchedulerGlobalHandler {
  @ExceptionHandler(NoCapacityException.class)
  ProblemDetail handle(NoCapacityException ex) { ... } // 409, code = KUSS_NO_CAPACITY
}
```

All errors leave as RFC 9457 `ProblemDetail` with `code` (`KUSS_*`) and `requestId`; no stack traces or SQL in `detail`.

### Utils: static helpers

```java
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class TimeSlotUtils {
  static boolean isOnHalfHourGrid(LocalTime time) { ... }
}
```

### Constants: messages and shared names in `common.constants`

```java
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ErrorMessageConstants {
  public static final String BOOKING_NOT_COMPLETED = "Booking could not be completed.";
  public static final String APPOINTMENT_NOT_FOUND = "Appointment not found.";
}
```

- Every error and `detail` message is a constant, even if it's used once.
- So is every name read by more than one class: headers (`HeaderConstants`), request attribute and MDC keys (`RequestConstants`), metric names (`MetricConstants`).
- A literal used in one class only stays `private static final` in that class.
- SQL stays as `private static final` text blocks in its repository, next to the method that runs it.

### Package layout: one subpackage per role

Each domain (`appointment`, `availability`, `catalog`, `audit`) is one package with the same subpackages. A class lives in the subpackage that matches its suffix from the table above. Shared code (`constants`, `error`, `util`, `web`) stays in `common`.

```
com.keyloop.scheduler.appointment
├── controller   AppointmentController
├── service      AppointmentService (interface)
│   └── impl     AppointmentServiceImpl, BookingAttemptHelper
├── repository   AppointmentRepository, AppointmentWriteRepository
├── entity       AppointmentEntity, AppointmentStatus
├── dto          AppointmentDto, BookAppointmentDto, ...
├── mapper       AppointmentMapper
├── config       (configuration classes)
└── util         CandidateSelectionUtils
```

A class used from another package is `public`, so package-private classes stay inside their subpackage.

### Test vs IT

- `AvailabilitySweepTest`: plain unit test, no Spring context, no Docker. Run by Surefire in `./mvnw test`.
- `AppointmentBookingIT`: Spring context + Testcontainers Postgres. Run by Failsafe in `./mvnw verify`.

Test methods are snake_case with the prefix `should_`, so the name states the expected behaviour:

```java
@Test
void should_return_409_when_vehicle_already_booked() { ... }

@Test
void should_book_exactly_k_appointments_when_n_requests_race_for_k_pairs() { ... }
```

## 2. Coding rules

### Variables are camelCase

```java
UUID dealershipId;           // not dealership_id, not DealershipId
```

### Booleans start with is / can / should / has + context

```java
boolean isSlotFree = freeBays > 0;
boolean hasQualifiedTechnician = !technicians.isEmpty();
boolean canCancel = appointment.getStartTime().isAfter(now);
// not: boolean free; boolean flag; boolean techOk;
```

### `@Transactional(readOnly = true)` on read paths

```java
@Transactional(readOnly = true)
public List<LocalTime> findAvailableStartTimes(UUID dealershipId, LocalDate date, String serviceType) { ... }
```

### Lists: `List.of` if never modified, `new ArrayList<>` if modified

```java
List<String> statuses = List.of("CONFIRMED", "CANCELLED");    // immutable
List<UUID> candidates = new ArrayList<>(freeTechnicianIds);   // we remove as we retry
candidates.remove(takenId);
```

### Optional only for return values that may be absent

```java
Optional<AppointmentEntity> findById(UUID id);          // ok: return value
// not: private Optional<String> note;                  field
// not: void book(Optional<UUID> technicianId);         parameter
// not: record BookingDto(Optional<String> note) {}     record component
```

### Builders for own domain types, `new` for JDK and framework types

```java
AppointmentEntity appointment = AppointmentEntity.builder()
    .vehicleId(vehicleId)
    .serviceBayId(bayId)
    .startTime(start)
    .build();
// not: new AppointmentEntity(vehicleId, bayId, start, ...)
List<UUID> candidates = new ArrayList<>(freeTechnicianIds);   // JDK type: new is fine
throw new SchedulerException(ErrorCode.KUSS_NO_CAPACITY);   // exceptions keep new
```

Applies to entities, DTOs and our own value objects.

### Stream API for collection transforms

```java
List<UUID> busyBayIds = appointments.stream()
    .map(AppointmentEntity::getServiceBayId)
    .toList();
```

### Set / HashMap for lookup and uniqueness

```java
Set<UUID> busyBays = new HashSet<>(busyBayIds);
boolean isBayFree = !busyBays.contains(bayId);            // O(1)
// not: busyBayIds.stream().anyMatch(id -> id.equals(bayId)) inside a loop
```

### Methods are at most 20 lines

Split long methods into small named steps so each one is unit-testable:

```java
public AppointmentDto book(BookAppointmentDto request, String idempotencyKey) {
  BookingContext context = loadContext(request);
  validateSlot(context);
  return appointmentMapper.toDto(insertWithRetry(context));
}
```

Enforced by Checkstyle `MethodLength` (`max = 20`) bound to `./mvnw verify`, which fails the build on violation. Test sources are excluded. The Checkstyle config itself is created in KUSS-04's skeleton phase.

### Data access: JPA only

- Spring Data repositories with JPQL `@Query`; `nativeQuery = true` only where JPQL can't express the statement. No `JdbcClient` in `src/main`.
- Booking, idempotency claim and audit writes go through entities with `saveAndFlush` inside each per-attempt transaction; the entities implement `Persistable` so the write stays one INSERT. Cancel is one atomic conditional JPQL `UPDATE` (`@Modifying`), and idempotency completion is one conditional `UPDATE` too. No explicit locks anywhere (no `@Lock`, no `SELECT ... FOR UPDATE`). SQLState is read from the exception cause chain.
- Partition DDL in `MaintenanceService` runs as `EntityManager` native queries; names come only from the date formatter or the regex.
- Tests may keep raw JDBC connections for lock and race harnesses.

```java
public interface AppointmentRepository extends JpaRepository<AppointmentEntity, UUID> {

  @Query("select count(a) > 0 from AppointmentEntity a where a.vehicleId = :vehicleId and a.status = AppointmentStatus.CONFIRMED and a.startTime < :end and a.endTime > :start")
  boolean existsVehicleOverlap(...);

  @Modifying(clearAutomatically = true)
  @Query("update AppointmentEntity a set a.status = AppointmentStatus.CANCELLED, a.cancelledAt = :now where a.id = :id and a.status = AppointmentStatus.CONFIRMED and a.startTime > :now")
  int cancelIfConfirmedAndNotStarted(@Param("id") UUID id, @Param("now") Instant now);
}
```

## 3. Comments: Javadoc only

No inline comments in the codebase, main or test: no `//` and no `/* */`. A name that needs a comment to explain it gets renamed or extracted into a well-named method. Tests use descriptive `should_*` names instead of `// given / when / then` markers.

The only comments are descriptive Javadoc (`/** */`), and they are required on core business methods: booking, availability, cancellation, idempotency and audit. Javadoc says what the method guarantees and why, not how it does it line by line. Skip it on getters, DTO records, controllers that only delegate, and obvious one-liners.

```java
/**
 * Books the first free bay + qualified technician pair for the whole service duration.
 * Retries with the next free pair when a concurrent booking takes the chosen one, and
 * replays the stored response when the same Idempotency-Key is sent again.
 *
 * @throws SchedulerException KUSS_VEHICLE_ALREADY_BOOKED or KUSS_NO_CAPACITY
 */
AppointmentDto book(BookAppointmentDto request, String idempotencyKey);
```

```java
// not: int attempts = 0; // retry counter
// not: // ponytail: safety cap, raise if needed
```

No `ponytail:` marker comments: deliberate shortcuts and their ceilings go in the plan or `docs/system-design.md`. This rule is checked in code review, not by Checkstyle.

## 4. Schema changes: Flyway only

Every table change (create, alter, drop, index, constraint, seed data) is a versioned Flyway migration in `src/main/resources/db/migration/`. Flyway records each one in its own `flyway_schema_history` table and checks its checksum on every start.

- File name: `V<version>__<description>.sql`
  - `<version>` is a sequential integer: `V1`, `V2`, `V3`. No gaps, no reuse.
  - Two underscores separate the version from the description.
  - `<description>` is lower snake_case and starts with a verb: `create`, `add`, `alter`, `drop`, `insert`, `rename`.

  ```text
  V1__create_schema.sql
  V2__insert_seed_data.sql
  V3__add_index_appointment_start_time.sql
  // not: V3__Index.sql, V3_add_index.sql, V3__AddIndex.sql, V2.1__fix.sql
  ```
- Hibernate never touches the schema: `spring.jpa.hibernate.ddl-auto=validate` and `spring.jpa.generate-ddl=false`. Startup fails if an `*Entity` and its table drift apart.
- A committed migration is never edited. To change something, add the next `V<version + 1>__<description>.sql`.
- `spring.flyway.clean-disabled=true`, so nothing can wipe the database.
- Only exception: the audit maintenance job creates tomorrow's `request_audit` day partitions and drops ones older than 14 days at runtime. It never changes columns, indexes or constraints, because partitions inherit those from the parent table that Flyway created.

## 5. Formatting: Spotless

Spotless formats the code and `./mvnw verify` runs `spotless:check`, which fails the build on any unformatted file. Run `./mvnw spotless:apply` before committing.

|             Files             |                                                      Formatter                                                       |
|-------------------------------|----------------------------------------------------------------------------------------------------------------------|
| Java, main and test           | google-java-format, Google style (2-space indent, 100 columns), plus unused imports removed and a fixed import order |
| `pom.xml`                     | sortPom                                                                                                              |
| `db/migration/*.sql`          | DBeaver SQL formatter                                                                                                |
| `*.yml`, `docker-compose.yml` | Jackson YAML                                                                                                         |
| `README.md`, `docs/*.md`      | flexmark (not `docs/brainstorm/`, `docs/agent-discussion/` or `plans/`)                                              |

- Formatter versions are pinned, so the same code always formats the same way.
- Format a Flyway migration before its first commit. Reformatting it afterwards changes its checksum, and Flyway refuses to start.
- Spotless runs before Checkstyle: the 20-line method limit is measured on formatted code.

## Developer decisions log

Each rule above that changed after the first version of this file keeps the agent's original selection here.

- **Comments (§3).** Agent selection: comments only where intent isn't obvious, plus `ponytail:` markers. **Developer decision (after review, 2026-10-31):** no inline comments; Javadoc only, required on core business methods; no `ponytail:` markers; checked in review — overrides. **Reason:** names explain the code, Javadoc documents the contract.
- **Test names (§1).** Agent selection: none. **Developer decision (after review, 2026-10-31):** snake_case `should_*` test methods, no `// given/when/then` — adds. **Reason:** the name states the expected behaviour.
- **Entities (§1).** Agent selection: none (no Lombok). **Developer decision (after review, 2026-10-31):** `@Getter @Setter @Builder` + protected no-args / private all-args constructors, never `@Data` — adds. **Reason:** `@Data` breaks lazy loading and loops on relations.
- **Object creation (§2).** Agent selection: constructors (`new AppointmentDto(...)`). **Developer decision (after review, 2026-10-31):** builders for own domain types; JDK/framework types and exceptions keep `new` — overrides. **Reason:** named fields read better than positional arguments.
- **Mapping (§1).** Agent selection: "Put mapping in the record (`from(...)` / `toEntity()`), not in a separate mapper class." **Developer decision (after review, 2026-10-31):** MapStruct `*Mapper` interfaces own every Entity ↔ Dto mapping — overrides. **Reason:** DTOs stay pure data; mapping lives in one generated, testable place.
- **Schema changes (§4).** Agent selection: Flyway migrations + `ddl-auto: validate`. **Developer decision (after review, 2026-10-31):** `validate` confirmed; every schema change via Flyway, `generate-ddl: false`, `clean-disabled: true`, never edit a committed migration, `request_audit` day partitions as the only runtime DDL — confirms + adds. **Reason:** one auditable schema history.
- **Migration names (§4).** Agent selection: `V1__schema.sql`, `V2__seed.sql`. **Developer decision (after review, 2026-10-31):** `V<version>__<description>.sql`, sequential integer, verb-first lower snake_case — overrides. **Reason:** the file name says what the migration does.
- **Package layout (§1).** Layout by role: `<domain>/{controller, service, service/impl, repository, entity, dto, mapper, config, util}`. **Developer decision (relayed by orchestrator, 2026-10-31):** adopted; existing files moved in one commit. **Reason:** a reader finds a class by its role, not by scanning one flat package.
- **Constants (§1).** Agent selection: literals inline at each use, attribute keys inside `RequestAuditFilter`, SQL in repositories. **Developer decision (after review, 2026-10-31):** messages and shared names in `common.constants` `*Constants` classes; one-class literals stay private; SQL stays in repositories (confirms red-team #13) — overrides. **Reason:** one place per message and name, no drift between duplicates.
- **Data access (§2).** Agent selection: JPA reads + JdbcClient writes (plan decision 1, A-024). **Developer decision (after review, 2026-10-31):** JPA only, incl. booking, idempotency, cancel, audit and partition DDL via native queries; tests may keep raw JDBC for lock/race harnesses — overrides. **Reason:** one data-access style; JPQL/@Query instead of hand-written JDBC.
- **Data access, no locks (§2).** Agent selection: cancel locks the row with `SELECT ... FOR UPDATE` / `@Lock(PESSIMISTIC_WRITE)` (A-023 #2). **Developer decision (after review, 2026-10-31):** cancel is one atomic conditional `UPDATE ... WHERE status = CONFIRMED AND start_time > now`, idempotency completion is one conditional `UPDATE`, and there are no explicit locks anywhere — **overrides** the lock prescription. **Reason:** the developer's original rule, "do not lock table, use atomic conditional update".
- **Boolean `isActive` is a breaking API change (§2).** Agent selection: persisted booleans named `active`. **Developer decision (after review, 2026-10-31):** the Java field, the repository methods and the JSON property become `isActive` (the column stays `active`) — **overrides**. This **breaks the API contract**: `GET /api/v1/dealerships/{id}` returns `isActive` instead of `active` on bays and technicians, and clients that read `active` must change. **Reason:** boolean fields start with `is`/`has`/`can`/`should`.
- **Formatting (§5).** Agent selection: none (Checkstyle `MethodLength` only). **Developer decision (after review, 2026-10-31):** Spotless with google-java-format, imports, sortPom, SQL, YAML, Markdown; `check` fails `./mvnw verify` — adds. **Reason:** one format, enforced by the build.
- **Java style (§5).** Agent selection: AOSP style (4-space), chosen by the KUSS-04 implementer to match the doc examples. **Developer decision (after review, 2026-10-31):** default Google style, 2-space, with the examples converted — overrides. **Reason:** keep the formatter's standard style; docs follow the code.

