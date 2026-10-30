---
name: review-code
description: "Review backend code (Java/Spring, PostgreSQL) before PRs or after implementing a story. Explores edge cases with native Explore subagents, dispatches code-reviewer, runs fix/verify cycles."
argument-hint: "[context] OR codebase [parallel]"
---

# Code Review

Technical rigor, evidence-based claims, verification over performative responses.
**YAGNI, KISS, DRY.** Be honest, brutal, concise. Evidence before claims.

## Default (No Arguments)

With context (recent changes/PR) → review it. Without arguments → `AskUserQuestion` (header "Review Operation"):

| Operation | Description |
|-----------|-------------|
| `(default)` | Review recent changes/PR |
| `codebase` | Full codebase scan & analysis |
| `codebase parallel` | Parallel multi-reviewer audit |

## Related Superpowers Skills (use, don't duplicate)

| Situation | Skill |
|-----------|-------|
| Receiving review feedback | `superpowers:receiving-code-review` |
| Dispatching a review | `superpowers:requesting-code-review` |
| Before any completion claim, commit, PR | `superpowers:verification-before-completion` |
| Over-engineering lens | `ponytail:ponytail-review` |

## Process

```
explore edge cases → code-reviewer → fix → verify
```

1. **Explore edge cases** — `references/edge-case-exploration.md` (native `Explore` subagent)
2. **Get SHAs** — `BASE_SHA=$(git merge-base main HEAD)`, `HEAD_SHA=$(git rev-parse HEAD)`
3. **Dispatch** `code-reviewer` subagent with: WHAT, PLAN, BASE_SHA, HEAD_SHA, edge-case findings
4. **Fix** Critical immediately, High before proceeding
5. **Verify** — build + full test suite (incl. Testcontainers concurrency tests) green, then claim

## Backend Review Checklist

- **Booking correctness**: no double-booking of bay, technician or vehicle over the full service duration under concurrent requests. Enforced by partial `EXCLUDE USING gist` constraints (`WHERE status = 'CONFIRMED'`, `[)` ranges). On `23P01`, check the constraint name: vehicle → `409` immediately; bay/tech → re-query and retry until no free pair is left. Backed by a concurrency test
- **Transactions**: boundaries correct; `@Transactional(readOnly = true)` on read paths; no external calls inside a DB transaction
- **Idempotency**: `Idempotency-Key` required on book and cancel, key row written in the same transaction as the booking/cancel, same payload → stored response replayed, different payload → `422`, in-flight → `409`
- **Request audit**: `request_audit` written async in its own transaction (`REQUIRES_NEW`), so rolled-back `409`/`500` are still audited; audit failures logged and swallowed; payload sanitized (no PII)
- **Queries**: indexes for availability lookups, no N+1, pagination on lists
- **API**: input validation at controller boundary, correct status codes (409 on conflict); all errors go through the single `*GlobalHandler` as RFC 9457 `ProblemDetail` with a `KUSS_*` `code` and `requestId`, with no stack traces or SQL in `detail`
- **Conventions**: `.claude/rules/java-conventions.md` (naming suffixes, records for DTOs, methods ≤ 20 lines)
- **Migrations**: forward-only, reversible-safe, no destructive change without note
- **Observability**: structured logs with correlation id, metrics on booking success/conflict

## Task-Managed Pipeline

**When:** 3+ changed files, parallel reviewer scopes, or fix cycles.
Each stage is a Task blocked by the previous. Re-review cycles limit 3, then escalate to user.
**Full protocol:** `references/task-management-reviews.md`

## Codebase Subcommands

| Subcommand | Reference |
|------------|-----------|
| `/review-code codebase` | `references/codebase-scan-workflow.md` |
| `/review-code codebase parallel` | `references/parallel-review-workflow.md` |

## Implement Handoff

Implement completes phase → review pipeline → all tasks complete → implement proceeds.
