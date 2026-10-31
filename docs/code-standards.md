# Code Standards

Java 21 + Spring Boot 4 conventions for the Unified Service Scheduler. Agents load the short checklist in `.claude/rules/java-conventions.md`; this file is the human-readable version with examples.

These conventions override ponytail's "no interface with a single implementation" rule for `*Service` / `*ServiceImpl`, and its `ponytail:` marker comments (see §3). Ponytail (minimal code, no speculative abstraction) still applies everywhere else.

## 1. Naming: class suffix → role

| Suffix | Role |
|---|---|
| `*Controller` | Request entry point (REST controller) |
| `*Service` | Service interface |
| `*ServiceImpl` | Implementation of that interface |
| `*Repository` | Spring Data JPA repository |
| `*Entity` | JPA entity, lower snake_case table name |
| `*Dto` | Java `record`, carries the DTO ↔ Entity mapping |
| `*GlobalHandler` | The single global exception handler |
| `*Utils` | Static helpers |
| `*Test` | Unit test (Surefire) |
| `*IT` | Integration test with Testcontainers (Failsafe) |

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

### Entity: lower snake_case table names via `@Table`

```java
@Entity
@Table(name = "service_bay")   // also: technician_skill, request_audit
class ServiceBayEntity { ... }
```

### Dto: always a `record`, owns the mapping

```java
public record AppointmentDto(UUID id, UUID vehicleId, Instant startTime, String status) {
    public static AppointmentDto from(AppointmentEntity entity) {
        return new AppointmentDto(entity.getId(), entity.getVehicleId(),
                entity.getStartTime(), entity.getStatus().name());
    }
}
```

Never `class AppointmentDto`. Put mapping in the record (`from(...)` / `toEntity()`), not in a separate mapper class.

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
final class TimeSlotUtils {
    private TimeSlotUtils() {}
    static boolean isOnHalfHourGrid(LocalTime time) { ... }
}
```

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
    return AppointmentDto.from(insertWithRetry(context));
}
```

Enforced by Checkstyle `MethodLength` (`max = 20`) bound to `./mvnw verify`, which fails the build on violation. Test sources are excluded. The Checkstyle config itself is created in KUSS-04's skeleton phase.

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
