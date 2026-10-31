# Task Management Integration

## Session-Scoped Reality

Claude Tasks are **ephemeral** — they die when the session ends. `~/.claude/tasks/` holds lock files only, NOT task data. Plan files (plan.md, phase-XX.md with checkboxes) are the **persistent** layer.

The **hydration pattern** bridges sessions:

```
┌──────────────────┐  Hydrate   ┌───────────────────┐
│ Plan Files       │ ─────────► │ Claude Tasks      │
│ (persistent)     │            │ (session-scoped)  │
│ [ ] Phase 1      │            │ ◼ pending         │
│ [ ] Phase 2      │            │ ◼ pending         │
└──────────────────┘            └───────────────────┘
                                        │ Work
                                        ▼
┌──────────────────┐  Sync-back ┌───────────────────┐
│ Plan Files       │ ◄───────── │ Task Updates      │
│ (updated)        │            │ (completed)       │
│ [x] Phase 1      │            │ ✓ completed       │
│ [ ] Phase 2      │            │ ◼ in_progress     │
└──────────────────┘            └───────────────────┘
```

- **Hydrate:** Read plan files → TaskCreate per unchecked `[ ]` item
- **Work:** TaskUpdate tracks in_progress/completed in real-time
- **Sync-back:** Update `[ ]` → `[x]` in phase files, update plan.md frontmatter status

## When to Create Tasks

**Default:** On — auto-hydrate after plan files are written
**Skip with:** `--no-tasks` flag in planning request
**3-Task Rule:** <3 phases → skip tasks (overhead exceeds benefit)

| Scenario | Tasks? | Why |
|----------|--------|-----|
| Multi-phase feature (3+ phases) | Yes | Track progress, enable parallel |
| Complex dependencies between phases | Yes | Automatic unblocking |
| Plan will be executed by implement | Yes | Seamless handoff |
| Single-phase quick fix | No | Just do it directly |
| Trivial 1-2 step plan | No | Overhead not worth it |

## Task Creation Patterns

### Phase-Level TaskCreate

```
TaskCreate(
  subject: "Add schema migrations and seed data",
  activeForm: "Adding schema migrations",
  description: "Flyway schema with EXCLUDE constraints + seed fixtures. See phase-01-schema-and-migrations.md",
  metadata: { phase: 1, priority: "P1", effort: "2h",
              planDir: "plans/261031-KUSS-04-booking-api/", phaseFile: "phase-01-schema-and-migrations.md" }
)
```

### Critical Step TaskCreate

For high-risk/complex steps within phases:

```
TaskCreate(
  subject: "Implement 23P01 retry loop",
  activeForm: "Implementing 23P01 retry loop",
  description: "Savepoint per attempt; vehicle conflict → 409, bay/tech conflict → re-query until no free pair",
  metadata: { phase: 3, step: "3.4", priority: "P1", effort: "1.5h",
              planDir: "plans/261031-KUSS-04-booking-api/", phaseFile: "phase-03-availability-and-booking-api.md",
              critical: true, riskLevel: "high" },
  addBlockedBy: ["{phase-2-task-id}"]
)
```

## Metadata & Naming Conventions

**Required metadata:** `phase`, `priority` (P1/P2/P3), `effort`, `planDir`, `phaseFile`
**Optional metadata:** `step`, `critical`, `riskLevel`, `dependencies`

**subject** (imperative): Action verb + deliverable, <60 chars
- "Add schema migrations", "Implement booking retry loop", "Create cancel endpoint"

**activeForm** (present continuous): Matches subject in -ing form
- "Adding schema migrations", "Implementing booking retry loop", "Creating cancel endpoint"

**description**: 1-2 sentences, concrete deliverables, reference phase file

## Dependency Chains

```
Phase 1 (no blockers)              ← start here
Phase 2 (addBlockedBy: [P1-id])    ← auto-unblocked when P1 completes
Phase 3 (addBlockedBy: [P2-id])
Step 3.4 (addBlockedBy: [P2-id])   ← critical steps share phase dependency
```

Use `addBlockedBy` for forward references ("I need X done first").
Use `addBlocks` when creating parent first ("X blocks these children").

## Implement Handoff Protocol

### Same-Session (planning → implement immediately)

1. Planning hydrates tasks → tasks exist in session
2. Implement Step 3: `TaskList` → finds existing tasks → picks them up
3. Implement skips re-creation, begins implementation directly

### Cross-Session (new session, resume plan)

1. User runs `/implement path/to/plan.md` in new session
2. Implement Step 3: `TaskList` → empty (tasks died with old session)
3. Implement reads plan files → re-hydrates from unchecked `[ ]` items
4. Already-checked `[x]` items = done, skip those

### Sync-Back (implement Step 6)

1. `TaskUpdate` marks all session tasks complete.
2. `project-manager` subagent runs full-plan sync-back:
   - Sweep all `phase-XX-*.md` files.
   - Reconcile completed tasks by metadata (`phase`, `phaseFile`).
   - Backfill stale completed checkboxes `[ ]` → `[x]` across all phases (not only current phase).
   - Update `plan.md` status/progress from actual checkbox state.
3. If any completed task cannot be mapped to a phase file, report unresolved mappings before claiming completion.
4. Git commit captures the state transition for next session.

## Quality Checks

After task hydration, verify:
- Dependency chain has no cycles
- All phases have corresponding tasks
- Required metadata fields present (phase, priority, effort, planDir, phaseFile)
- Task count matches unchecked `[ ]` items in plan files
- Output: `✓ Hydrated [N] phase tasks + [M] critical step tasks with dependency chain`
