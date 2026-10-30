---
paths: ["**/*.java"]
---

# Java Conventions (checklist)

Full rules with examples: `docs/code-standards.md`. These project conventions override ponytail's "no interface with a single implementation" rule for `*Service` / `*ServiceImpl` only; ponytail still applies everywhere else.

## Class suffix → role

- `*Controller` — request entry point (REST controller)
- `*Service` — service interface; `*ServiceImpl` — its implementation
- `*Repository` — Spring Data JPA repository
- `*Entity` — JPA entity; `@Table(name = "...")` with a lower snake_case table name (`service_bay`, `technician_skill`, `request_audit`)
- `*Dto` — Java `record`, never a class; holds the DTO ↔ Entity mapping (`static from(XEntity)`, `toEntity()`)
- `*GlobalHandler` — the single `@RestControllerAdvice` exception handler
- `*Utils` — static helpers only (final class, private constructor)
- `*Test` — unit test (Surefire); `*IT` — integration test with Testcontainers (Failsafe)

## Coding

- [ ] Variables camelCase; booleans start with `is`/`can`/`should`/`has` + context (`isSlotFree`, `hasQualifiedTechnician`)
- [ ] `@Transactional(readOnly = true)` on read paths
- [ ] Unmodified lists → `List.of(...)`; lists you add to or remove from → `new ArrayList<>(...)`
- [ ] `Optional` only as a return type for a maybe-absent value. Never in fields, parameters or record components
- [ ] Stream API for collection transforms
- [ ] Lookups and uniqueness → `Set` / `HashMap`, not `List` scans or index loops
- [ ] Each method ≤ 20 lines; split into smaller methods for testability. Enforced by Checkstyle `MethodLength` (max 20, main sources only) failing `./mvnw verify`. The Checkstyle config is created in KUSS-04's skeleton phase.
