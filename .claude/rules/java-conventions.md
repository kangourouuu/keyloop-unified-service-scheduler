---
paths: ["**/*.java"]
---

# Java Conventions (checklist)

Full rules with examples: `docs/code-standards.md`. These project conventions override ponytail's "no interface with a single implementation" rule for `*Service` / `*ServiceImpl`. They also override ponytail's "no new dependency" rule for Lombok and MapStruct, and its `ponytail:` marker comments: never write them, and record deliberate shortcuts in the plan or docs. Ponytail still applies everywhere else.

## Class suffix → role

- `*Controller` — request entry point (REST controller)
- `*Service` — service interface; `*ServiceImpl` — its implementation
- `*Repository` — Spring Data JPA repository
- `*Entity` — JPA entity; `@Table(name = "...")` with a lower snake_case table name (`service_bay`, `technician_skill`, `request_audit`). Lombok `@Getter @Setter @Builder @NoArgsConstructor(access = AccessLevel.PROTECTED) @AllArgsConstructor(access = AccessLevel.PRIVATE)`; never `@Data`, `@EqualsAndHashCode` or `@ToString`
- `*Dto` — Java `record` with Lombok `@Builder`, never a class; no mapping code inside
- `*Mapper` — MapStruct `@Mapper(componentModel = "spring")` interface; owns every Entity ↔ Dto mapping (`toDto`, `toEntity`)
- `*GlobalHandler` — the single `@RestControllerAdvice` exception handler
- `*Utils` — static helpers only (final class, private constructor)
- `*Test` — unit test (Surefire); `*IT` — integration test with Testcontainers (Failsafe)
- Test methods: snake_case with prefix `should_` (`should_return_409_when_vehicle_already_booked`)

## Coding

- [ ] Variables camelCase; booleans start with `is`/`can`/`should`/`has` + context (`isSlotFree`, `hasQualifiedTechnician`)
- [ ] `@Transactional(readOnly = true)` on read paths
- [ ] Unmodified lists → `List.of(...)`; lists you add to or remove from → `new ArrayList<>(...)`
- [ ] `Optional` only as a return type for a maybe-absent value. Never in fields, parameters or record components
- [ ] Stream API for collection transforms
- [ ] Build own domain types (entities, DTOs, value objects) with `X.builder()...build()`, never `new X(params)`. JDK/framework types (`new ArrayList<>()`, `new HashSet<>()`, `ProblemDetail`) and exceptions keep `new`
- [ ] Lookups and uniqueness → `Set` / `HashMap`, not `List` scans or index loops
- [ ] Each method ≤ 20 lines; split into smaller methods for testability. Enforced by Checkstyle `MethodLength` (max 20, main sources only) failing `./mvnw verify`. The Checkstyle config is created in KUSS-04's skeleton phase.

## Comments

- [ ] No inline comments (`//`, `/* */`) anywhere in main or test code. Use descriptive names instead; tests carry no `// given/when/then` markers
- [ ] Javadoc (`/** */`) only, and required on core business methods: booking, availability, cancellation, idempotency, audit. Describe what the method guarantees and why, not how
- [ ] No `ponytail:` marker comments; record deliberate shortcuts and their ceilings in the plan or docs
- [ ] Checked in code review (not by Checkstyle)
