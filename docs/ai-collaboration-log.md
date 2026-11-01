# AI Collaboration Log

This is a running, dated evidence log. The README's "AI Collaboration Narrative" and `docs/video-outline.md` draw from it. Every entry cites a file or a discussion id. `A-xxx` ids are in `docs/agent-discussion/antigravity-to-claude.md` and `C-xxx` ids in `claude-to-antigravity.md`. Entries are added at the bottom and past entries are never edited.

## PDF documentation requirements → where they live

`[x]` means the section has content. `[ ]` means it waits on a `TODO(design)` or `TODO(impl)`.

**Part 1: System Design Document** → `docs/system-design.md`

- [x] Architecture diagram → §2 (Mermaid flowchart)
- [x] Role of each component → §3
- [x] Data flow → §4.1 availability, §4.2 booking (sequence diagram, key claim vs retry loop, deadlocks), §4.3 conflict/409/503, §4.4 cancel (atomic conditional update), §4.5 audit (six-month retention)
- [x] Technologies with justifications → §5
- [x] Observability strategy (logging, metrics, tracing, request audit) → §6
- [x] Section on how GenAI assisted the design, including human overrides → §9
- [x] Assumptions for ambiguous requirements ("A Note on Ambiguity") → §8
- [x] Design approved by the user (2026-10-30, A-021) → status line at the top; every developer decision → §10 Decision record

**Part 2: Service implementation (backend)**

- [x] Scalability, performance, reliability, maintainability and observability considered → `system-design.md` §5–§7
- [x] Client mocked with cURL examples or an OpenAPI spec → `README.md` "API walkthrough (curl)" (9 steps from the seed data) and Swagger UI `/swagger-ui.html`, OpenAPI `/v3/api-docs`

**Deliverables**

- [x] 1. System Design Document → `docs/system-design.md`
- [x] 2a. README build/run/test instructions → `README.md` "Prerequisites" (JDK 21, Docker or Podman), "Build and test", "Run", "Quality gates"
- [x] 2b. README AI Collaboration Narrative, covering strategy, verification and refinement, and final quality → `README.md` "AI Collaboration Narrative"
- [x] 2c. Test suite for the core business logic → `src/test` (38 `*Test` + 91 `*IT` methods at `638222d`), summarised in `system-design.md` §7 and `README.md` "Build and test"
- [x] 3. Video (5–10 min) talking points for the five PDF bullets → `docs/video-outline.md` §1–§5

**Evaluation criteria graded on the docs**

- [x] AI Engineering & Verification → README narrative, plus this log
- [x] Communication & Presentation → all four docs, plus the video

## Cast

|                                                                 Actor                                                                 |                                                                                                                                                                    Role                                                                                                                                                                     |
|---------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| User (human)                                                                                                                          | Wrote the starting draft and the workflow. Makes every scope, trade-off and approval decision.                                                                                                                                                                                                                                              |
| Antigravity (Gemini 4 Argon for the brainstorm, per the orchestrator brief; Gemini 3.8 Flash high for the plan review, per `plan.md`) | Brainstorm partner with the user. Wrote `docs/brainstorm/first-draft-high-level.md`. Cross-reviews plans and code.                                                                                                                                                                                                                          |
| Claude Code (reviewer session)                                                                                                        | Set up `.claude/` and the git hooks. Cross-reviews Antigravity's design through `docs/agent-discussion/`.                                                                                                                                                                                                                                   |
| Orca coordinator (Claude Code)                                                                                                        | Dispatches supervised Claude workers. Routes `needs-user` decisions to the user as popups.                                                                                                                                                                                                                                                  |
| Claude workers                                                                                                                        | task_da0c43b28d51: design discussion and `.claude/` alignment. task_e9b1349f3449: plan-story (the plan landed as KUSS-02, not KUSS-01 as first briefed). task_bce368d917cd / task_525f5aa6bfd7: these docs (KUSS-06).                                                                                                                       |
| Red-team reviewers (Claude subagents)                                                                                                 | Four hostile lenses on the plan: security, failure modes, assumptions, scope (`plans/261030-KUSS-02-…/reports/red-team-*-261030.md`).                                                                                                                                                                                                       |
| KUSS-04 workers                                                                                                                       | Main implementer: Claude Haiku 5.5 high (phases 1–6, 7b). Second Haiku worker: phase 7a in its own worktree. Claude Opus: debugger (deadlock storm), alignment reviewer with a code-reviewer subagent, fix list. Antigravity (Gemini 3.8 Flash): cross-reviewer. A fix worker applied F-1..F-8. A KUSS-05 QA worker owns the running stack. |

## Log

### 2026-10-29: human draft and workflow setup

- **Human.** The user wrote `docs/brainstorm/user-draft.md`, which covers:
  - the stack: Java 21, Boot 4, PostgreSQL, Redis, RabbitMQ, WebSocket, Docker;
  - assumptions: 500M bookings/month, a 20k read / 2k write peak, ≤100 ms latency;
  - brainstorm ideas: race conditions, a WebSocket and cache for "real-time", an atomic conditional update instead of table locks;
  - the agent workflow they wanted.
- **Claude Code, directed by the user** (the "Step to work with agent" list in `user-draft.md`; confirmed in C-001):
  - `CLAUDE.md` in the Karpathy guideline format, plus project workflow gates;
  - `.claude/rules/` (development, naming, orchestration, primary workflow);
  - skills `plan-story` (with `red-team` and `validate`), `implement` and `review-code`, plus their agents;
  - `.githooks/` enforcing `feature/KUSS-XX-…` branches and `[KUSS-XX] …` commits, and blocking `.env` files.

### 2026-10-29 ~21:00: brainstorm, Antigravity with the user

|     Id      |                                                              What the AI proposed                                                               |                                                               What the human decided                                                                |
|-------------|-------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------|
| A-002       | Challenged 500M bookings/month: that is 6B services a year, more than the world's car fleet.                                                    | **Accepted.** Sized to ~2k dealerships and ~60k bookings/day, with peaks of ~1,500 read / ~100 write RPS.                                           |
| A-003       | Checked the stack against Part 2 (backend only, no payment step).                                                                               | **User-led:** the user said virtual threads plus PostgreSQL is enough. WebSocket and `PENDING` holds dropped; Redis and RabbitMQ moved to "future". |
| A-004/A-005 | Pushed back on the user's "1 technician serves X=3 cars at once". The PDF says "for the entire duration", and X=3 breaks `EXCLUDE` constraints. | **User agreed** that X=3 was "overthinking" (A-006), giving strict 1:1.                                                                             |
| A-007       | Proposed the domain rules: multi-skill technicians, general bays, auto-assignment, no vehicle overlap, a 30-minute grid in local hours.         | **Accepted** all four (A-008).                                                                                                                      |
| A-009       | API scope: Flyway seed data, no admin CRUD, an idempotent POST, cancellation.                                                                   | Not decided by the user directly. Folded into the draft and C-011.                                                                                  |

### 2026-10-29 21:02–21:15: Claude Code cross-review of the decisions

- C-001: Opened the append-only, two-file discussion log so that every agent decision leaves a trace.
- C-002: Framed the traffic numbers as the reason no extra infrastructure is needed. Made the **concurrency test**, not a load test, the correctness proof.
- C-003: **Checked against the PostgreSQL docs** (btree_gist, CREATE TABLE). It proposed:
  - three partial `EXCLUDE` constraints with `[)` bounds;
  - SQLSTATE `23P01` mapped to `409`;
  - one transaction per candidate attempt.

  It also warned that "virtual threads" must not be sold as the scaling story, because the Hikari pool is the real limit.

- C-005: Two gaps in the rules:

  - qualification and dealership scoping are checked by the application, not the DB;
  - a UTC-modulo `CHECK` breaks for +05:30/+05:45 zones, so grid validation must happen in local time at the API.
- C-006: Cut scope:
  - drop the Idempotency-Key, since the vehicle constraint already rejects a duplicate POST;
  - derive the customer from the vehicle;
  - no H2, because it has no gist support.

  It also added observability, which was missing.

### 2026-10-29 21:22–21:51: review of the first draft (rejections and corrections)

- **C-007.** Seven issues found in `first-draft-high-level.md`:

  1. A vehicle `23P01` would burn retries.
  2. The Idempotency-Key was still there.
  3. `customerId` was still in the body.
  4. It claimed "100% DB-guaranteed … even raw SQL cannot double-book", which is true for overlap only.
  5. Over-build: a `version` column, high-cardinality metric labels, pinned-carrier metrics.
  6. **Unmeasured latency numbers.**
  7. It cited RFC 7807 instead of RFC 9457.

  A-010 fixed items 1–5 and 7.

- **Correction still open (C-007 #6).** A-010 marked the draft "fully aligned with C-002–C-007", but the "~1–3ms" and "< 2ms" figures are still in draft §6 #5 and §7.1 #5. C-008 confirmed only items 1–5 and 7, so #6 went untracked. `docs/system-design.md` leaves those figures out.

- **Rejected regression (C-008).** The rewrite in A-010 added a `pg_try_advisory_xact_lock`-per-30-minute-bucket layer next to `EXCLUDE`. It brought key hashing, multi-bucket acquisition and an unmeasured "10/10 in 1 pass" claim. Claude Code rejected it in favour of re-querying and retrying until no free pair is left. That always terminates, because each `23P01` means a committed booking took the capacity. Antigravity agreed (A-011) and applied the change (A-012). Claude Code re-checked the file (C-010).

- C-008 also caught a leftover "idempotent write APIs" and asked for an explicit "no authN/authZ" assumption. Both were applied in A-012.

- **Leftover inconsistency, logged here and not yet raised.** Draft §2, "DB Concurrency" row, still says "bounded optimistic retry", which contradicts the retry-until-exhausted rule in §4 and §7.2.

### 2026-10-29 21:53: protocol fix, agents may not settle user decisions

- The protocol header of both discussion files gained a **User decisions** rule. Any call that belongs to the user is posted with `Status: needs-user` plus 2–4 options, and the orchestrator shows it to the user. A-013 acknowledged it.
- The fix addressed a real gap. The concurrency mechanism, dropping the Idempotency-Key, the POST body, the extra endpoints and the technician pick rule had all been settled between agents. C-011 now puts all five to the user, with draft approval. **Status: pending.**

### 2026-10-30: Orca orchestration, parallel workers

- The coordinator dispatched three supervised Claude workers with strict file ownership (see Cast). Workers ask the coordinator their questions instead of opening local prompts.
- task_bce368d917cd mapped the PDF documentation requirements above. It wrote:
  - `docs/system-design.md`, a draft with a pending-approval banner and `TODO(design)` gaps;
  - the README skeleton and its narrative;
  - `docs/video-outline.md`.

  It validated both Mermaid diagrams by rendering them with `@mermaid-js/mermaid-cli` 11.4.2.

- Known drift, waiting on approval: `.claude/rules/development-rules.md:19,21` and several `.claude/agents/*.md` files still name Redis/RabbitMQ/WebSocket (C-009, owned by task_da0c43b28d51). `user-draft.md` planned a second draft with "gemini 3.8 flash", and no second draft exists yet.

### 2026-10-30 08:21–08:38: approval deferred, leftovers closed

- **Human.** The user deferred the C-011 decision. The draft was not approved yet, and Claude Code held the `.claude/` alignment (C-012).
- **Correction: unmeasured latency figures.** The orchestrator brought the open C-007 #6 back as C-013 #1. Antigravity replaced "~1–3ms" and "< 2ms" with "to be measured in integration/concurrency tests" (A-014). Claude Code confirmed by grep (C-014). This closes the gap logged on 2026-10-29.
- **Correction: "bounded optimistic retry".** Draft §2 contradicted the agreed retry-until-no-free-pair model. It was raised as C-013 #2, reworded in A-014 and verified in C-014.

### 2026-10-30 20:58–22:02: developer review of the draft (user decisions)

The user reviewed `first-draft-high-level.md` and recorded each call in §12 as **Agent selection → Developer decision → Reason**. Antigravity wrote the decisions into the draft.

|                                                                                              Decision                                                                                               |             Kind              |      Id      |                                                                                                                                                             Notes                                                                                                                                                             |
|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------|--------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Approach A: partial `EXCLUDE USING gist` and the retry-until-exhausted loop                                                                                                                         | **confirms** C-011 #1         | A-018        | —                                                                                                                                                                                                                                                                                                                             |
| `request_audit`: every request, async, `REQUIRES_NEW`, daily partitions + BRIN, 14-day retention                                                                                                    | **adds**                      | A-015, A-016 | No agent had proposed auditing.                                                                                                                                                                                                                                                                                               |
| Required `Idempotency-Key` on book and cancel: `idempotency_key` table in the same transaction, 24 h TTL, replay, `422` on mismatch, `409` while in flight                                          | **overrides** C-011 #2        | A-016        | The agents (C-006, C-007 #2, A-012) had dropped it because the vehicle `EXCLUDE` already rejects a duplicate. The user's reason: a client retrying after a timeout should get the stored response back, not an error, and cancellation needs the same protection. The vehicle constraint stays as the second line of defence. |
| `@RestControllerAdvice` with RFC 9457 `ProblemDetail` + `code` (`KUSS_*`) + `requestId`, an enum catalog of 9 codes, and the code written to `request_audit.error_code` and the metric `result` tag | **adds**                      | A-017        | Also settled the open cancel rule: `409 KUSS_APPOINTMENT_ALREADY_STARTED`.                                                                                                                                                                                                                                                    |
| Request signing deferred to the roadmap with client auth                                                                                                                                            | **adds** (roadmap)            | A-019        | An unkeyed SHA-256 header can simply be recomputed. Real protection needs HMAC or RFC 9421.                                                                                                                                                                                                                                   |
| Minimal POST body with the customer derived from the vehicle; the extra endpoints and cancel; technician = fewest scheduled minutes                                                                 | **confirms** C-011 #3, #4, #5 | A-020        | —                                                                                                                                                                                                                                                                                                                             |

- **Approval.** The orchestrator relayed the user's approval of the draft on 2026-10-30 to this worker. The discussion files have no "resolved" entry quoting it yet: A-020 still says "full draft approval remains deferred pending final user 'Done' signal", and C-010 asked for such an entry. The draft's header `Status:` line still says "Ready for … Review", and its §8 heading still says "Proposed Defaults — Confirming Scope".

### 2026-10-30: corrections caught by the orchestrator

- **A "Developer decision" line was written before the user had decided.** A-018 records the orchestrator's correction. Items that had not been reviewed yet (C-011 #3–#5) were reset to `Developer decision: pending — awaiting user review`. The confirmations in A-020 came only after the user reviewed them. The marking rule (keep "Agent selection", add "Developer decision (after review)" with overrides/adds/confirms and a reason) exists to prevent exactly this.
- **Mermaid diagrams broken by unquoted labels and `;`.**
  - The 2026-10-29 draft used `\n` inside labels and the unquoted edge label `|Scale Trigger: >5k Read RPS|` (draft §5).
  - Its sequence diagram had a `;` in a message ("Compute endTime = startTime + duration; verify 30m grid …", §7.2). Mermaid reads `;` as a statement separator.
  - The user saw the broken render. The current draft uses `<br/>`, quoted edge labels and "then" instead of `;`. All three of its diagrams now render with `@mermaid-js/mermaid-cli` 11.4.2 (checked by task_525f5aa6bfd7).
  - The rule for every later doc: `<br/>` only, quote edge labels containing `(`, `:` or `>`, and no `;`.

### 2026-10-30: ticket mapping

Each ticket has its own `feature/KUSS-XX-*` branch, `[KUSS-XX]` commits and its own PR (`docs/brainstorm/user-draft.md`, "Tickets"):

| Ticket  |                                   Scope                                   |                                  Evidence so far                                  |
|---------|---------------------------------------------------------------------------|-----------------------------------------------------------------------------------|
| KUSS-01 | Project setup: `.claude/`, `.githooks/`, CLAUDE.md, the Antigravity setup | `.claude/`, `.githooks/`, C-001                                                   |
| KUSS-02 | Brainstorm the first draft, then plan                                     | `docs/brainstorm/*`, `docs/agent-discussion/*`; plans waiting (task_e9b1349f3449) |
| KUSS-03 | Visualise the spec with answer-me-with-html                               | —                                                                                 |
| KUSS-04 | Implementation and review (TDD); all implementation phases                | —                                                                                 |
| KUSS-05 | Retest and report bugs                                                    | —                                                                                 |
| KUSS-06 | Documentation deliverables (these four files)                             | `docs/system-design.md`, `README.md`, this log, `docs/video-outline.md`           |

### 2026-10-30: docs refresh (KUSS-06, task_525f5aa6bfd7)

- Rewrote `docs/system-design.md` from the approved draft:
  - removed the banner;
  - added the idempotency, audit and error-catalog components, data flow and observability;
  - rewrote §9 from the decision trail, including the human overrides;
  - re-rendered both diagrams cleanly.
- Resolved the earlier `TODO(design)` lines on cancelling a past appointment (A-017), C-011 approval (A-018, A-020) and `.claude/` stack drift (now fixed in `.claude/rules/development-rules.md:19`).
- **New gap found in the approved draft (not yet raised with the agents).** Draft §7.2 inserts the `idempotency_key` row *after* the appointment INSERT. With that order, a concurrent same-key duplicate with the same body hits `excl_appointment_vehicle_overlap` first and gets `409 KUSS_VEHICLE_ALREADY_BOOKED`. Mandatory test 6 in draft §12 expects `KUSS_IDEMPOTENCY_IN_FLIGHT`. This is recorded as a `TODO(design)` in `system-design.md` §4.2 for the plan to settle.

### 2026-10-30 22:15–22:16: approval recorded, `.claude/` aligned (KUSS-01)

- **Human.** The user approved the draft. The approval is now in the log (A-021, `Status: resolved`). It closes the gap noted on 2026-10-30, when no approval entry existed.
- Claude Code aligned `.claude/` to the approved stack (C-015):
  - removed Redis/RabbitMQ/WebSocket from the rules and agents ("roadmap only");
  - added the review checklist items for `EXCLUDE`/`23P01`, the idempotency key, the async audit and `KUSS_*`;
  - created `docs/code-standards.md` and `.claude/rules/java-conventions.md`.

  It also flagged `CLAUDE.md` line 69 as outside its ownership; that line has since been fixed.

### 2026-10-30 22:19–23:20: plan written, cross-reviewed by both agents (KUSS-02)

- **C-017: Claude Code's gap list on the approved draft.** Ten gaps, without reopening §12. The ones that changed the design:
  - Gap 1: a retry inside one transaction cannot work after a failed INSERT.
  - Gap 2: `KUSS_IDEMPOTENCY_IN_FLIGHT` is unreachable when the key is inserted last. The orchestrator and this docs worker found the same gap independently (log entry above, C-018).
  - Gap 3: a partitioned PK must include `occurred_at`.
  - Gap 4: async audit on unbounded virtual threads could starve the pool.
  - Gap 7: the time contract was undefined.
- **A-022: Antigravity added a pitfall Claude Code had missed.** Hibernate marks a transaction rollback-only on `23P01` even with savepoints, so writes moved to `JdbcClient`.
  - It proposed `INSERT … ON CONFLICT DO NOTHING` for the claim. Claude Code kept a plain `INSERT` so that `23505` still produces `IN_FLIGHT` (C-019).
  - Savepoints were then dropped entirely in favour of a whole transaction per attempt.
- **A-023 / C-020: Antigravity's plan review.**
  - **Accepted:** a partition start-up window, where rows could land in DEFAULT and block today's partition. Claude Code corrected its proposed fix: `ContextRefreshedEvent` fires *after* the web server starts, so only a `SmartLifecycle` at `Integer.MIN_VALUE` closes the window.
  - **Accepted:** the cancel path moved fully onto `JdbcClient`.
  - **Rejected:** a Jackson property, because it is deserialization-only. A London/New York offset test covers the risk instead.
  - A-024 / C-021: plan agreed by both agents.

### 2026-10-31: red team, each finding decided by the developer

- **Process.** Four Claude red-team reviewers (security, failure modes, assumptions, scope) produced 34 raw findings, deduplicated to 15 (7 High, 8 Medium) in `reports/red-team-adjudication-261030.md`. The orchestrator put **each finding to the user one by one**.
- **Result:** 14 accepted (2 modified), 1 rejected, all confirmed by the developer (`plan.md` "Red Team Review", C-022).
- **Root-cause questions before deciding.**
  - **RT #1, deadlocks.** The research had said booking-vs-booking "should not cycle". The user asked why concurrent bookings would deadlock at all. The answer: PostgreSQL checks an exclusion constraint *after* inserting its own index tuple (`execIndexing.c`, "unprincipled deadlock"); unique B-trees avoid it by speculative insertion, exclusion constraints don't. Accepted after that explanation:

    - retry `40P01`;
    - `deadlock_timeout=100ms` in compose and Testcontainers, so the loser aborts fast instead of after the 1 s default;
    - never assert zero deadlocks;
    - correct research-02 §7.

    *An AI research claim was wrong and was caught by another AI pass. The human made the call once they understood the mechanism.*

  - **Partition ownership.** Who owns `request_audit` partitions, Flyway or the job? This led to the Flyway-only rule, recorded in the draft (A-026):

    - Agent selection: Flyway `V1`/`V2` + `ddl-auto: validate`.
    - **Developer decision (after review, 2026-10-31):** every schema change via Flyway, `generate-ddl=false`, never edit a committed migration, and the partition job as the only runtime DDL — **confirms + adds**.
    - **Reason:** one auditable schema history; daily partitions cannot be versioned migrations.
- **Draft wording fixed from RT #10** (A-025). Each fix uses the format Agent selection → Developer decision (after review, 2026-10-31) — overrides → Reason "red-team finding #10":
  - §11 #9 now states what "no auth" means: anyone can read all data and cancel any appointment, with no rate limiting.
  - §8 `GET /customers` returns vehicles without VIN or plate.
  - The audit scope changed from `/api/**` to all paths except actuator and API docs.
- **Other decisions that changed the design:**
  - RT #8: a vehicle precheck before capacity.
  - RT #4: a 5 s timeout per attempt, Hikari 20/3 s.
  - RT #7: an early two-connection race test in phase 02.
  - RT #13: one `candidateStarts` function for reads and writes, and a 90-day horizon.
  - RT #14: compose hardening and an explicit "no rate limiting".
  - RT #15 was rejected: removing DEFAULT/`response_status` conflicts with the approved schema, and an early-return cancel would break replay.

### 2026-10-31: validation session (`plan.md` "Validation Log", Session 1)

Four questions went to the user in one grouped ask through the orchestrator. Each is recorded as Agent selection → Developer decision (after review, 2026-10-31) → Reason:

|      Topic       |        Agent selection (Recommended option)         |                                                                                                                                   Developer decision — kind                                                                                                                                   |                                                Reason                                                |
|------------------|-----------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------|
| Environment      | The user installs JDK 21 + Docker Desktop or Colima | **Overrides.** The orchestrator provisions the environment. The machine runs **Podman** (no Docker CLI, no JDK). The plan must support Testcontainers on Podman (`DOCKER_HOST`, socket override, Ryuk) and both `podman compose` and `docker compose`.                                        | The plan had assumed a runtime the user doesn't have.                                                |
| Spring Boot line | 4.0.9 as researched                                 | **Confirms** 4.0.9 (not 4.1.2)                                                                                                                                                                                                                                                                | The verified version set needs no re-check.                                                          |
| Run mode         | `/implement --auto`, `/review-code` after phase 07  | **Overrides** the reviewer. A Claude worker on **Haiku 5.5 high** implements. **One cross-review after phase 07** by Antigravity (Gemini 3.8 Flash) and a Claude **Opus 5.5 high** worker, discussing in `docs/agent-discussion/`. No user pause between phases; each passes `./mvnw verify`. | With a cheaper implementer, the plan's exact specs and an objective gate are what carry the quality. |
| Git              | Commit per phase, push after `/review-code`         | **Overrides.** Commit locally per phase. **The user is the last gate:** no push until they approve after the cross-review. PR bodies say `Closes #X` (issues #1–#6 = KUSS-01..06).                                                                                                            | Nothing leaves the machine unapproved.                                                               |

**Delegation by complexity** (orchestrator pattern, visible across this log):

- Haiku for mechanical work: implementation from a fully specified plan.
- Opus for design, planning review and code review.
- Gemini (Antigravity) for brainstorming and an independent second opinion.
- The user decides.

### 2026-10-31: conventions added during review (`docs/code-standards.md` "Developer decisions log", `plan.md` "Conventions added after validation")

All 9 are binding for KUSS-04:

| # |      Topic      |                        Agent selection                        |                                   Developer decision (after review, 2026-10-31) — kind                                    |                         Reason                         |
|---|-----------------|---------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------|
| 1 | Comments        | Comments where intent isn't obvious, plus `ponytail:` markers | No inline comments; Javadoc only, required on core business methods; no `ponytail:` markers — **overrides**               | Names explain the code; Javadoc documents the contract |
| 2 | Test names      | none                                                          | snake_case `should_*`, no given/when/then comments — **adds**                                                             | The name states the expected behaviour                 |
| 3 | Entities        | none (no Lombok)                                              | `@Getter @Setter @Builder`, protected no-args / private all-args constructors, never `@Data` — **adds**                   | `@Data` breaks lazy loading and loops on relations     |
| 4 | Object creation | Constructors                                                  | Builders for our own domain types; JDK types and exceptions keep `new` — **overrides**                                    | Named fields read better                               |
| 5 | Mapping         | `from()` / `toEntity()` inside DTO records                    | MapStruct `*Mapper` interfaces — **overrides**                                                                            | DTOs stay pure data; mapping is generated and testable |
| 6 | Formatting      | none                                                          | Spotless (google-java-format, imports, sortPom, SQL, YAML, Markdown); `check` fails `./mvnw verify` — **adds**            | One format, enforced by the build                      |
| 7 | Schema changes  | Flyway + `validate`                                           | Flyway only, `generate-ddl=false`, `clean-disabled=true`, the partition job as the only runtime DDL — **confirms + adds** | One auditable schema history                           |
| 8 | Migration names | `V1__schema.sql`                                              | `V<n>__<verb>_<what>.sql` — **overrides**                                                                                 | The file name says what it does                        |
| 9 | Constants       | Literals inline                                               | Messages and shared names in `common.constants` `*Constants`; SQL stays in repositories (confirms RT #13) — **overrides** | One place per message, no drift                        |

Note: `code-standards.md` also overrides parts of ponytail explicitly: `*Service` + `*ServiceImpl`, Lombok/MapStruct as new dependencies, and no `ponytail:` markers. The human chose team conventions over the minimal-code default.

### 2026-10-31: tickets merged as squashed PRs

- KUSS-01..03 are merged into `master` as PRs #7–#9, one squash-merged PR per ticket. They cover setup, brainstorm + plan, and the HTML explainer `docs/visualization/kuss-design.html` (11 sections plus screenshots).
- The rule comes from `CLAUDE.md` workflow step 6: one story per branch, and a branch carrying more than one story is split before the PR.
- KUSS-04 (implementation), KUSS-05 (retest) and KUSS-06 (these docs) are open.

### 2026-10-31: docs refresh (KUSS-06)

- `docs/system-design.md` now follows the approved design plus the plan's corrections:
  - claim-first idempotency and a whole transaction per attempt;
  - the vehicle precheck;
  - deadlocks and `deadlock_timeout=100ms`;
  - bounded audit executor, audit scope, partition start-up;
  - Flyway ownership;
  - the consequences of no auth;
  - a §10 decision record.
- This resolved the three earlier `TODO(design)` lines: key insert order (C-017 #2), the `desiredStartTime` format (C-017 #7) and Prometheus in compose (phase 01: `db` + `app` only).
- Both diagrams render with mermaid-cli 11.4.2, with no `;` and no `\n`, and every special-character edge label is quoted.
- The README has Docker/Podman prerequisites, build/run/test commands from the plan, and the quality gates. The Podman socket lookup and rootful check were run on the developer machine. The `./mvnw` and compose commands are still `TODO(impl)`, because JDK 21 and the skeleton are not there yet.

### 2026-10-31 11:40: Markdown formatting, an agent formatted another agent's files

- **What happened.** At 11:40:09 the KUSS-04 implementer ran `./mvnw spotless:apply`. The flexmark Markdown formatter (in scope per `docs/code-standards.md` §5) rewrote this docs worker's four uncommitted files.
- **Damage.** Mostly cosmetic: padded tables, loose lists, trailing newlines. One real break: list items starting with `#1,` … `#7,` (the C-017 gap list above) became empty bullets plus H1 headings `# 1, …`. This worker found it by checking the headings and empty list items in all four files, then rewrote the list as "Gap N:". No content was lost: no edit was in progress at 11:40:09.
- **Decision:**
  - Agent selection: Spotless formats `README.md` and `docs/*.md` with flexmark inside `./mvnw verify` (conventions #6, 2026-10-31).
  - **Developer decision (after review, 2026-10-31):** keep Markdown formatting in `./mvnw verify`. After each edit to its four files, the docs owner runs `source <scratchpad>/kuss-env.sh && ./mvnw -q spotless:apply` (once `pom.xml` exists, which it does after phase 01) — **confirms** the earlier Markdown choice.
  - **Reason:** an agent formatted another agent's uncommitted files, so the docs owner now formats on every edit, and the implementer's `verify` never fails on, or rewrites, the docs.
- **Writing rule for flexmark-stable Markdown:** never start a list item or a line with `#` followed by a digit. Write "Gap 2", "RT #2" or "item 2" instead.
- **Drift found while applying the decision.** In `pom.xml` (modified 11:40:38, owned by the KUSS-04 implementer), the Spotless configuration has no `<markdown>` step: only `java`, `pom`, `sql` and `yaml`. So `./mvnw -q spotless:apply` and `spotless:check` currently pass without touching the four docs (both run, exit 0, files unchanged). That contradicts the decision above and conventions #6. The fix is to restore the Markdown step in the pom, which is outside this worker's ownership; it was escalated to the user. The docs owner keeps running `spotless:apply` after every edit, so the docs are formatted once the step is back.
- **Drift resolved (11:55).** The implementer restored the `<markdown>` step (flexmark on `README.md` and `docs/*.md`, excluding `docs/brainstorm/`, `docs/agent-discussion/` and `plans/`). At commit `7f07abd`, `spotless:apply` formatted the four docs again. The only change since this worker's last saved copy was one removed blank line in the log, so tables, Mermaid blocks and edits are intact. From now on the docs owner formats on every edit, as decided above.

### 2026-10-31: data access is JPA only (developer decision)

- Agent selection: JPA reads + `JdbcClient` writes (plan decision 1, agreed by both agents in A-024). The reason was A-022's pitfall: Hibernate marks a transaction rollback-only on `23P01`, which would break a retry inside one transaction.
- **Developer decision (after review, 2026-10-31):** JPA only in `src/main`, no `JdbcClient`:
  - booking and the idempotency claim;
  - cancel with `@Lock(PESSIMISTIC_WRITE)`;
  - audit writes;
  - partition DDL through `EntityManager` native queries.

  Tests may keep raw JDBC for the lock and race harnesses. — **overrides**

- **Reason:** one data-access style, JPQL/`@Query` instead of hand-written JDBC.

- **Why this is still correct.** A-022's pitfall mattered for savepoints inside one transaction. The plan had already moved to one transaction per attempt (C-017 Gap 1), so a rollback-only attempt is simply discarded and the next one starts fresh. Two things become requirements:

  - each INSERT must be flushed inside its attempt;
  - the `23P01`/`40P01`/`23505` classification must happen outside the `TransactionTemplate` callback.

  A-023 #2's stale-entity concern (JPA load + JDBC update) disappears.

- Docs updated:

  - `system-design.md`: the 4 `JdbcClient` mentions (diagram node, §3 components, §4.4 cancel step 2, §5 technologies) keep their original text as "Agent selection", with the decision line added;
  - the §4.2 write-path notes and a §10 decision row;
  - `video-outline.md` §5 ("writes outside Hibernate" no longer true).

### 2026-10-31: lock ordering moved to the roadmap (developer decision)

- **What happened.** During KUSS-04 the implementer measured **1005 deadlock retries** (`40P01`) in a 20-way booking burst and added pessimistic locks (bay, then technician) before the insert to stop them.
- Agent selection: lock ordering (bay → tech `PESSIMISTIC_WRITE`) before the `INSERT`, in V1.
- **Developer decision (after review, 2026-10-31):** lock ordering is a **roadmap** item for scaling, triggered by the deadlock retry metric (`scheduler.booking.gist_retries{conflict_type=deadlock}`). V1 = `EXCLUDE` + retry only. The developer reverted the locks — **overrides**.
- **Reason:** the deadlock storm is being fixed at its root cause: concurrent requests herding onto the same candidate pair, and the lack of jitter between retries. It is not being fixed by adding a second locking scheme next to the constraints.
- **Pattern.** This is the same call as C-008, where an advisory-lock layer was rejected: the agent reached for a locking layer to treat a symptom, and the human asked why the symptom occurs. RT #1 had already predicted that exclusion inserts deadlock by design; 1005 retries for 20 requests shows the retry loop works but the contention pattern is wasteful.
- Docs updated: `system-design.md` §7 roadmap row (trigger = the deadlock retry metric) and a §10 decision row.

### 2026-10-31: KUSS-04 cross-review, Opus + Gemini (`plans/261030-KUSS-02-…/reports/review-261031-kuss04-alignment.md`)

- **Who and how.**
  - A Claude Opus 5.5 worker reviewed the 16 KUSS-04 commits in review-only mode, with a code-reviewer subagent pass.
  - Antigravity (Gemini 3.8 Flash) cross-reviewed the Opus report through `docs/agent-discussion/`: C-023 → A-028 → C-024 for the findings, and C-025 → A-029 → C-026 for the fix list.
- **Evidence first.**
  - `./mvnw -B verify` gave BUILD SUCCESS: 35 Surefire and 72 Failsafe tests, 0 failures, Spotless and Checkstyle clean.
  - `BookingConcurrencyIT` ran 3 times, 4/4 each time. The deadlock counts were 2, 82 and 35 `40P01` per run, taken from Hibernate WARN lines.
  - Greps confirmed: JPA only in `src/main`, no bay/tech locks, no inline comments, nothing pushed.
- **Decision alignment.** Every recorded decision was checked against the code, 77 rows in all: **70 ALIGNED, 5 PARTIAL, 0 DEVIATION, 2 N-A**. Both agents agreed on every status.
- **Findings:** 0 Critical, **1 High**, 2 Medium, 9 Low.
  - **H-1:** user input could become a Prometheus tag through the idempotency error paths (unbounded cardinality).
  - The code copied a wrong list from the plan (RT #13c) exactly. So the defect was in an agent-written plan, and the implementer followed it faithfully.
  - Antigravity confirmed the severity and the fix.
- **Agent-to-agent refinement.**
  - Gemini added a routing target Opus had missed: `docs/visualization/kuss-design.html` still says `KUSS_INTERNAL_ERROR` in 4 places (A-029).
  - It argued that the 5 s per-attempt timeout should stay, with the deadline checked before each attempt and each sleep. Changing `setTimeout` per call on a shared `TransactionTemplate` is not thread-safe (Q6).
- **The developer made 3 decisions** on the review's open questions, plus two accepted fixes:

|  #   |           Topic           |                                           Agent selection                                            |                                                            Developer decision (after review, 2026-10-31) — kind                                                             |                                            Reason                                             |
|------|---------------------------|------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------|
| DD-1 | `40P01` retry bound (M-1) | `MAX_ATTEMPTS = 100`, exponent from the overall attempt, sleep before every retry, no total deadline | 10 s wall-clock deadline per booking request; the backoff exponent counts deadlocks only; no sleep after the last attempt — **adds**                                        | Without it, one request can run 10–18 minutes after the client has given up                   |
| DD-2 | Overload status (L-5)     | No free connection gives `500 KUSS_INTERNAL_ERROR`, WARN without a stack trace                       | DB pool exhausted or the DD-1 deadline hit gives `503` + `Retry-After` with the new code `KUSS_SERVICE_BUSY`. No key is stored, so a same-key retry is safe — **overrides** | A 500 reads as a non-retryable server bug; overload is temporary and retryable                |
| —    | Trace ids in prod logs    | Phase 07 criterion: prod JSON log lines carry `traceId`                                              | The MDC wiring is accepted, with no per-request INFO line — **overrides** the criterion                                                                                     | Not stated in the relayed decision; recorded later in `plan.md` as "no per-request log noise" |
| fix  | Key expiry (L-2)          | Daily cleanup only; keys live 24–48 h, expiry unchecked on read                                      | Exact 24 h TTL checked on read — **adds**                                                                                                                                   | Make the documented 24 h contract exact                                                       |
| fix  | Read dates (L-6, F-7)     | No bounds on `date` for availability or the appointment list                                         | Horizon plus a lower bound; out of range gives 400 — **adds**                                                                                                               | A far date overflowed `timestamptz` and gave a 500 with a stack trace from user input         |

- **Docs updated (this worker):** in `system-design.md`, the §4.2 bounds (DD-1, DD-2, the deadlock backoff), the error catalog (new `KUSS_SERVICE_BUSY` 503 row, "no DB connection" trimmed from `KUSS_INTERNAL_ERROR`), POST codes `+503`, the key TTL in §3.1 and assumption 8, read-date bounds in §4.1, the tracing note and metric tags in §6, and five §10 rows.
- **Routed to other owners, not edited here:** the brainstorm draft §8/§9/§12 (Antigravity), plan phases 03/05/07 and `plan.md`, and `docs/visualization/kuss-design.html` (see the review's routing table).
- **Pattern.** The review ran mechanically on a cheaper implementer's output, and its one High finding traced back to the plan, not to the implementer. Every contract change (status code, deadline, logging criterion) went back to the human.

### 2026-10-31: KUSS-04 implementation, orchestrated by model

The orchestrator (a Claude Code coordinator session) delegated by complexity. Sources: `plan.md` "Execution and review", the alignment review, and this session.

|                          Work                          |                            Who (model)                             |                      Why this model                       |
|--------------------------------------------------------|--------------------------------------------------------------------|-----------------------------------------------------------|
| Phases 1–6 and 7b, TDD, `./mvnw verify` per phase      | Claude Haiku 5.5 high (`/implement --auto`)                        | Mechanical once the plan names exact files, tests and SQL |
| Phase 7a (async audit + partition and key maintenance) | A second Haiku worker in its own worktree, run in parallel         | No code dependency on phases 4–6                          |
| Deadlock-storm diagnosis                               | Claude Opus (debugger agent)                                       | Root-cause analysis                                       |
| Decision-alignment review (77 rows) and fix list       | Claude Opus, with a code-reviewer subagent                         | Judgment across design, plan and code                     |
| Cross-review of the review                             | Antigravity, Gemini 3.8 Flash high                                 | Independent second opinion                                |
| Docs (KUSS-06), with read-only verification            | Claude docs worker, Gemini via `agy` and a Claude Explore subagent | Writing by one owner; checking by two other models        |

**Parallel 7a:**

- Agent selection: "Order is strict".
- **Developer decision (after review, 2026-10-31):** phase 07 splits into 7a (built in parallel by another Haiku worker after phases 2 and 3, then cherry-picked: `911fd54`, `d95cbcc`) and 7b (metrics, probes, logging, by the main implementer after phase 6) — **overrides**.
- **Reason:** 7a has no code dependency on phases 4–6.
- The review found it ALIGNED (E-1).

### 2026-10-31: implementer deviations caught by the human

|      Deviation      |                           Agent selection (implementer)                           |                                      Developer decision (after review, 2026-10-31) — kind                                       |                                 Reason                                  |
|---------------------|-----------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------|
| Java format         | AOSP style (4-space), to match the doc examples                                   | Default Google style (2-space), examples converted — **overrides** (`code-standards.md` decisions log)                          | Keep the documented format                                              |
| Markdown formatting | Removed the `<markdown>` step from Spotless after it reformatted the docs (11:40) | Markdown stays in `verify`; the docs owner runs `spotless:apply` after each edit — **confirms** convention #6 (see 11:40 entry) | An agent formatted another agent's files; fix the process, not the gate |
| Deadlock storm      | Bay → technician `PESSIMISTIC_WRITE` locks before insert (`d536d09`)              | Reverted (`ab4300c`); V1 = `EXCLUDE` + retry, lock ordering is roadmap — **overrides** (A-027)                                  | Fix the root cause, not the symptom                                     |

### 2026-10-31: root cause of the deadlock storm (Opus debugger) and the backoff

- **Symptom:** the implementer measured 1005 deadlock retries in one 20-way burst.
- **Root cause, found by a Claude Opus debugger: a livelock, not a capacity problem.**
  - PostgreSQL breaks only one exclusion-index wait cycle per `deadlock_timeout`. The median gap between deadlock aborts was 101 ms, i.e. one per 100 ms `deadlock_timeout` (figure from the orchestrator's summary; the debugger's report is not in the repo).
  - Each victim retried at once and re-inserted a conflicting index entry, so 17–18 inserters stayed knotted (`plan.md` "Execution and review").
- **Fix 1 (`ab4300c`):** a randomized backoff before each `40P01` retry, starting at `deadlock_timeout` and doubling up to 3.2–6.4 s, with no connection held while waiting.
- **DD-1 then exposed the remaining gap.** With the new 10 s deadline, `BookingConcurrencyIT` failed 3/3 runs: victims returned before the knot drained, and 11–16 requests per run hit the deadline as `503`. The fix worker raised this for developer review instead of loosening the test.
- **First-wait decision:**
  - Agent selection: the first wait starts at `deadlock_timeout` (100–200 ms).
  - **Developer decision (after review, 2026-10-31):** first wait 1.6–3.2 s, doubling once to the 3.2–6.4 s cap, keeping the 10 s deadline — **overrides** the 100 ms start.
  - **Reason:** measured 5/5 green with zero `503`s and the slowest burst at 4.8 s. Only burst deadlock losers wait; normal bookings never do.
  - The code is `BookingDeadline.nextDeadlockBackoff` (`DEADLOCK_BACKOFF_MILLIS = 1600`, shift capped at 2).
- **Pattern:** treating the symptom (more locks) was rejected twice, in C-008 and here. Both times the human asked *why*, and the fix moved to the mechanism.

### 2026-10-31: post-review developer decisions applied in code (`plan.md` "Execution and review")

|            #             |                                Agent selection                                 |                                       Developer decision (after review, 2026-10-31) — kind                                        |                                            Reason                                             |
|--------------------------|--------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------|
| Trace ids                | `traceId` on prod log lines (phase 07 criterion)                               | MDC wiring only; `traceId`/`spanId` on WARN/ERROR lines, no per-request INFO — **overrides**                                      | No per-request log noise (this resolves the reason left open in the cross-review entry above) |
| L-2 key TTL              | Daily purge only, keys live 24–48 h                                            | A key older than 24 h is absent on read; the claim deletes it and stores the new request in the same transaction — **adds**       | The 24 h TTL holds exactly, whatever the purge schedule                                       |
| F-7 date bounds          | Availability: horizon only, `200` + empty for past dates. Day list: unbounded. | Availability today..+90 days, day list −365..+90 days (dealership zone), else `400` — **adds** bounds, **overrides** the 200      | Past availability is meaningless; far dates overflowed `timestamptz` and gave 500             |
| Convention #11, packages | none (flat packages per domain)                                                | `<domain>/{controller, service, service/impl, repository, entity, dto, mapper, config, util}`, shared code in `common` — **adds** | Find a class by its role                                                                      |

### 2026-10-31: KUSS-06 finalization (branch `feature/KUSS-06-documentation-deliverables`)

- All four docs were rewritten against the code at `99d0056`:
  - controllers, DTOs and seed ids for the README curl walkthrough;
  - `BookingDeadline`, `IdempotencyServiceImpl`, `RequestAuditFilter`, `MaintenanceServiceImpl` and `MetricConstants` for the design doc.
- Every `TODO(impl)` and `TODO(design)` is resolved.
- **Constraints respected:** no compose run, no app start (the KUSS-05 QA worker owns ports 5432/8080), no git. The walkthrough commands are derived from the code and the HTTP tests (`AppointmentApiIT`), not run live by this worker.
- **Delegated verification, at the user's request** ("delegate for both claude and gemini"), both read-only:
  - Gemini 3.8 Flash (`agy --mode plan`) fact-checked `system-design.md` against `src/main`.
  - A Claude Explore subagent checked every README command, path, payload and expected status against the controllers, DTOs and tests.
  - Their findings and the fixes are in the next entry.

### 2026-10-31: KUSS-06 delegated verification results

- **Gemini could not run.** The `agy` call failed twice:
  - First, a flag-order mistake by this worker: `-p` swallowed `--model`.
  - Then `RESOURCE_EXHAUSTED (429)`: "Individual quota reached … Resets in 158h48m". That is account-wide, so it was not retried.
  - The design-doc fact-check went to a second Claude subagent instead. Both checks were read-only: no build, no compose, no git.
- **README check (Claude Explore subagent):** curl steps 1–9, seed ids, paths, headers, body fields, jq selectors, the expected codes and the error table were all confirmed against controllers, DTOs, `SchedulerGlobalHandler` and `AppointmentApiIT`. Two errors were fixed:
  - Spotless/Checkstyle are bound to `validate`, so `./mvnw test` also fails on format or method length.
  - The unit-test count is 36 methods, not 37: this worker's grep had counted a helper.
- **Design-doc check (Claude Explore subagent):** 4 mismatches, all fixed:
  1. `KUSS_VALIDATION_FAILED` also tags framework 405/406/415 errors, which keep their own status.
  2. `503 KUSS_SERVICE_BUSY` covers **booking only**; pool exhaustion on cancel or reads is `500`.
  3. The images are `eclipse-temurin:21-jdk`/`21-jre`.
  4. A booking may start exactly at "now".

  Five omissions were added:
  - catalog also owns the bay and technician entities;
  - replays count as `confirmed`, and pre-service 400s are not in `scheduler_booking_requests_total`;
  - the audit payload is null over 4096 bytes;
  - the default profile uses Hikari's defaults (10 connections, 30 s);
  - `app` is published on all interfaces, which matters with no auth.

- **Pattern.** The writer and the checkers were separate. Every finding cited `file:line` and was checked before it was applied. The 37→36 slip was the writer's own and was caught by the checker.

### 2026-11-01: KUSS-06 docs aligned with the KUSS-04 tip (`638222d`)

The design doc, README, video outline and visual explainer were re-checked against the code at `638222d`. Earlier entries stay as written; where they no longer describe the code, this entry and the new rows in `system-design.md` §10 and the explainer's panel I override them.

|                              Earlier statement (entry)                              |                                                                             Current state at `638222d`                                                                             |                                   Source                                   |
|-------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------|
| Cancel with `@Lock(PESSIMISTIC_WRITE)` (2026-10-31 data-access entry)               | One atomic conditional JPQL `UPDATE`, `cancelIfConfirmedAndNotStarted`; no explicit locks anywhere. `404` missing, `200` already cancelled, `409 KUSS_APPOINTMENT_ALREADY_STARTED` | `AppointmentRepository.java:90-100`, `AppointmentServiceImpl.java:132-144` |
| `503 KUSS_SERVICE_BUSY` covers booking only (2026-10-31 verification entry)         | `503` + `Retry-After: 1` on every endpoint for a pool timeout or a statement timeout                                                                                               | `SchedulerGlobalHandler.java:44-62`, `BookingConflictHelper.java:39-42`    |
| 14-day audit retention (2026-10-30 decisions)                                       | Six months: older partitions dropped, older default rows deleted; idempotency keys deleted after 24 h                                                                              | `MaintenanceServiceImpl.java:30,54-63`                                     |
| Pre-service 400s are not in `scheduler_booking_requests_total` (verification entry) | Boundary 400s on `POST /appointments` are counted with `service_type=unknown`                                                                                                      | `SchedulerGlobalHandler.java:139-153`                                      |
| Bays and technicians expose `active`                                                | JSON property `isActive`, a breaking API change                                                                                                                                    | `ServiceBayDto.java:7`, `TechnicianDto.java:8`                             |

- Also documented: the idempotency completion is one conditional `@Modifying` `UPDATE` (`IdempotencyKeyRepository.java:16-23`); `EntityManager` only in `MaintenanceServiceImpl` (`MaintenanceServiceImpl.java:38`); the `*Helper` components and `final` `*Utils` with private constructors; unique customer email (`CustomerEntity.java:31`, `V1__create_schema.sql:86`); `/error` and Tomcat-level ProblemDetails (`SchedulerErrorController.java`, `ProblemDetailErrorReportValve.java`); `/actuator` index not audited (`RequestAuditFilter.java:38-39`); availability `serviceType` `@NotBlank @Size(max = 40)` (`AvailabilityController.java:29`); `deadlock_timeout=100ms` required in every deployment (`BookingDeadline.java:9-15`).
- **Test counts** were taken by reading `src/test` at `638222d`, not from a run: 38 `*Test` and 91 `*IT` methods. The HTTP tests are now `AppointmentCancelApiIT`, `AppointmentConflictApiIT`, `AppointmentReadApiIT` and `AppointmentValidationApiIT` (formerly `AppointmentApiIT`), plus `ServiceBusyIT`, `PoolExhaustionIT` and `IdempotencyServiceImplTest`.

<!-- Append new dated entries below: each implemented phase, cross-review findings, test evidence, PR links. -->
