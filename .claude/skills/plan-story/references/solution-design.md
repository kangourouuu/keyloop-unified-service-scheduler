# Solution Design

## Core Principles

Follow these fundamental principles:
- **YAGNI** (You Aren't Gonna Need It) - Don't add functionality until necessary
- **KISS** (Keep It Simple, Stupid) - Prefer simple solutions over complex ones
- **DRY** (Don't Repeat Yourself) - Avoid code duplication

## Design Activities

### Technical Trade-off Analysis
- Evaluate multiple approaches for each requirement
- Compare pros and cons of different solutions
- Consider short-term vs long-term implications
- Balance complexity with maintainability
- Assess development effort vs benefit
- Recommend optimal solution based on current best practices

### Booking Correctness (check first)
- Bay, technician and vehicle overlap is prevented by the partial `EXCLUDE USING gist` constraints, not app-level locks
- `23P01`: vehicle constraint → `409` at once; bay/technician constraint → re-query free pairs and retry until none left (savepoint per attempt)
- Technician qualification (`technician_skill`) and dealership scope are checked in the service layer — the DB enforces overlap only
- Idempotency key row is written in the same transaction as the booking/cancel
- Every booking-path change keeps the concurrency test (N parallel requests, K free pairs → exactly K `201`)

### Security Assessment
- Validate input at the controller boundary (DTO, 30-minute grid, opening hours, future time)
- Errors leave only as `ProblemDetail` with a `KUSS_*` code; no stack traces, SQL or PII in `detail` or `request_audit`
- Parameterized queries only; no secrets in git
- No AuthN/AuthZ in V1 (roadmap) — don't plan it unless the story asks

### Performance
- Indexed reads for availability and daily schedules; no N+1; pagination on lists
- `@Transactional(readOnly = true)` on read paths; no external calls inside a DB transaction
- Redis, RabbitMQ and WebSocket are roadmap only (`docs/brainstorm/first-draft-high-level.md` §10) — don't plan caches or message brokers

### Edge Cases & Failure Modes
- `[)` ranges: back-to-back slots (09:00–10:00, 10:00–11:00) both succeed
- Timezones: grid and opening hours in dealership-local time, storage as UTC `timestamptz`
- Rollback leaves no booking and no idempotency key, so a client retry runs fresh
- Rolled-back `409`/`500` are still audited (`REQUIRES_NEW`); an audit failure never fails the request
- Retry loop terminates in at most K attempts; same key in flight → `409 KUSS_IDEMPOTENCY_IN_FLIGHT`

### Architecture Design
- One stateless Spring Boot service + PostgreSQL; correctness lives in the DB
- Class roles per `.claude/rules/java-conventions.md`
- API contracts match the API surface and error catalog in `docs/system-design.md`; flag any change as breaking

## Best Practices

- Document design decisions and rationale; resolve open `TODO(design)` items in `docs/system-design.md` explicitly
- Plan observability: structured logs with `requestId`, Micrometer metrics on booking success / conflict / retries
- Design with testing in mind: each phase names the `*Test` / `*IT` cases it needs (`senior-qa` agent generates them)
- Flyway migrations are forward-only
