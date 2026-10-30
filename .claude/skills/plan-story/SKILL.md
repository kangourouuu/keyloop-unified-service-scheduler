---
name: plan-story
description: "Plan a KUSS story: implementations, design architectures, create technical roadmaps with detailed phases. Use for feature planning, system design, solution architecture, implementation strategy, phase documentation."
argument-hint: "[task] OR red-team|validate"
license: MIT
---

# Planning

Create detailed technical implementation plans through research, codebase analysis, solution design, and comprehensive documentation.

## Default (No Arguments)

If invoked with a task description, proceed with planning workflow. If invoked WITHOUT arguments or with unclear intent, use `AskUserQuestion` to present available operations:

| Operation | Description |
|-----------|-------------|
| `(default)` | Create implementation plan for a task |
| `red-team` | Adversarial plan review |
| `validate` | Critical questions interview |

Present as options via `AskUserQuestion` with header "Planning Operation", question "What would you like to do?".

## Workflow Modes

Default: `--auto` (analyze task complexity and auto-pick mode).

| Flag | Mode | Research | Red Team | Validation | Implement Flag |
|------|------|----------|----------|------------|-----------|
| `--auto` | Auto-detect | Follows mode | Follows mode | Follows mode | Follows mode |
| `--fast` | Fast | Skip | Skip | Skip | `--auto` |
| `--hard` | Hard | 2 researchers | Yes | Optional | (none) |
| `--parallel` | Parallel | 2 researchers | Yes | Optional | `--parallel` |
| `--two` | Two approaches | 2+ researchers | After selection | After selection | (none) |

Add `--no-tasks` to skip task hydration in any mode.

Load: `references/workflow-modes.md` for auto-detection logic, per-mode workflows, context reminders.

## When to Use

- Planning new feature implementations
- Architecting system designs
- Evaluating technical approaches
- Creating implementation roadmaps
- Breaking down complex requirements

## Core Responsibilities & Rules

Always honoring **YAGNI**, **KISS**, and **DRY** principles.
**Be honest, be brutal, straight to the point, and be concise.**

### 1. Research & Analysis
Load: `references/research-phase.md`
**Skip if:** Fast mode or provided with researcher reports

### 2. Codebase Understanding
Load: `references/codebase-understanding.md`
**Skip if:** Provided with exploration reports

### 3. Solution Design
Load: `references/solution-design.md`

### 4. Plan Creation & Organization
Load: `references/plan-organization.md`

### 5. Task Breakdown & Output Standards
Load: `references/output-standards.md`

## Workflow Process

1. **Pre-Creation Check** → Resolve active plan per `.claude/rules/naming-and-paths.md`
2. **Mode Detection** → Auto-detect or use explicit flag (see `workflow-modes.md`)
3. **Research Phase** → Spawn researchers (skip in fast mode)
4. **Codebase Analysis** → Read docs, native `Explore` subagent if needed
5. **Plan Documentation** → Write comprehensive plan via planner subagent
6. **Red Team Review** → Use `Skill` tool: `plan-story` with args `red-team {plan-path}` (hard/parallel/two modes)
7. **Post-Plan Validation** → Use `Skill` tool: `plan-story` with args `validate {plan-path}` (hard/parallel/two modes)
8. **Hydrate Tasks** → Create Claude Tasks from phases (default on, `--no-tasks` to skip)
9. **Context Reminder** → Output implement command with absolute path (MANDATORY)

## Output Requirements

- DO NOT implement code - only create plans
- Respond with plan file path and summary
- Ensure self-contained plans with necessary context
- Include code snippets/pseudocode when clarifying
- Fully respect the `.claude/rules/development-rules.md` file

## Task Management

Plan files = persistent. Tasks = session-scoped. Hydration bridges the gap.

**Default:** Auto-hydrate tasks after plan files are written. Skip with `--no-tasks`.
**3-Task Rule:** <3 phases → skip task creation.

Load: `references/task-management.md` for hydration pattern, TaskCreate patterns, implement handoff protocol.

### Hydration Workflow
1. Write plan.md + phase files (persistent layer)
2. TaskCreate per phase with `addBlockedBy` chain
3. TaskCreate for critical/high-risk steps within phases
4. Metadata: phase, priority, effort, planDir, phaseFile
5. Implement picks up via TaskList (same session) or re-hydrates (new session)

## Active Plan State

Resolve per `.claude/rules/naming-and-paths.md`:
- **Existing plan for the branch's KUSS-XX** → Ask "Continue? [Y/n]"
- **None** → Create `plans/{YYMMDD}-KUSS-XX-{slug}/`
Reports always go to `{plan-dir}/reports/`.

### Important
DO NOT create plans or reports in USER directory.
ALWAYS create plans or reports in CURRENT WORKING PROJECT DIRECTORY.

## Subcommands

| Subcommand | Reference | Purpose |
|------------|-----------|---------|
| `/plan-story red-team` | `references/red-team-workflow.md` | Adversarial plan review with hostile reviewers |
| `/plan-story validate` | `references/validate-workflow.md` | Validate plan with critical questions interview |

## Quality Standards

- Thorough and specific, consider long-term maintainability
- Research thoroughly when uncertain
- Address security and performance concerns
- Detailed enough for junior developers
- Validate against existing codebase patterns

**Remember:** Plan quality determines implementation success. Be comprehensive and consider all solution aspects.
