# Development Rules

**IMPORTANT:** Activate the skills needed for the task. Follow **YAGNI - KISS - DRY**.

## General

- **[IMPORTANT] Ponytail for every coding task**: activate `ponytail:ponytail` for any task that writes, refactors, fixes, reviews, or designs code, or picks a dependency.
- **[IMPORTANT] Brainstorm before new work**: use `superpowers:brainstorming` before designing a feature or changing behavior.
- **[IMPORTANT] RTK for commands**: run shell commands per `RTK.md` (`rtk proxy <cmd>` only when condensed output is unusable).
- **CodeGraph**: if `.codegraph/` exists at repo root, use `codegraph_explore` before grep/read, and tell subagents to do the same.
- **Codebase search (native)**: targeted lookup → Glob/Grep/Read directly; broad sweep → native `Explore` subagent(s), parallel, one per area. No custom scout skill.
- Paths, branch and commit format: `.claude/rules/naming-and-paths.md`.
- Use `gh` for GitHub, `psql` / `docker compose exec` for debugging Postgres.
- **[IMPORTANT]** Follow structure and standards in `./docs` (`system-design.md`, `code-standards.md`).
- **[IMPORTANT]** Implement real code — no simulated implementations or fake data to make things pass.

## Stack

- V1: Java 21 (virtual threads) + Spring Boot 4, PostgreSQL, Docker Compose. Redis, RabbitMQ and WebSocket are roadmap only (`docs/brainstorm/first-draft-high-level.md` §10), so don't add them to code or `docker-compose.yml`.
- Build/test through the project wrapper (`./mvnw` or `./gradlew`, whichever exists) — never a globally installed tool.
- Integration tests use Testcontainers against real Postgres, not in-memory substitutes (no H2: it has no `EXCLUDE`/gist).
- Booking correctness (no double-booking of bay, technician or vehicle for the whole service duration) is enforced by partial `EXCLUDE USING gist` constraints. On a bay or technician conflict (`23P01`), re-query free pairs and retry until none are left; a vehicle conflict returns `409` immediately. This must keep a concurrency test (N parallel requests, K free pairs → exactly K `201`).

## Code Quality

- No syntax/compile errors: run the build after every change.
- Readability over strict style; match surrounding code.
- Validate input at API boundaries; handle errors that could lose data.
- Keep files focused and small (~200 lines); split by responsibility, not by layer ceremony.
- Comments only where intent is not obvious from naming (and `ponytail:` markers for deliberate shortcuts).
- Java conventions: `docs/code-standards.md` (agent checklist: `.claude/rules/java-conventions.md`).
- Use the `code-reviewer` agent after every implementation.

## Pre-commit/Push

- Build + tests pass before commit; never skip or disable failing tests.
- No secrets (`.env`, keys, credentials) in git.
- Commit format from `naming-and-paths.md`; focused commits, one story per branch.
