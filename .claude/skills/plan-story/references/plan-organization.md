# Plan Creation & Organization

## Directory Structure

### Plan Location

`plans/{YYMMDD}-KUSS-XX-{slug}/` per `.claude/rules/naming-and-paths.md`.

**Example:** `plans/261029-KUSS-03-booking-concurrency/`

### File Organization

IN CURRENT WORKING PROJECT DIRECTORY:
```
{plan-dir}/                                    # plans/{YYMMDD}-KUSS-XX-{slug}/
├── research/
│   ├── researcher-XX-report.md
│   └── ...
├── reports/
│   ├── explore-report.md
│   ├── researcher-report.md
│   └── ...
├── plan.md                                    # Overview access point
├── phase-01-schema-and-migrations.md          # Flyway/Liquibase schema
├── phase-02-booking-domain.md                 # Entities, repositories
├── phase-03-availability-and-booking-api.md   # REST endpoints
└── phase-04-concurrency-tests.md              # Testcontainers tests
```

### Task Hydration

After creating plan.md and phase files, hydrate tasks (unless `--no-tasks`):
1. TaskCreate per phase with `addBlockedBy` dependency chain
2. Add critical step tasks for high-risk items
3. See `task-management.md` for patterns and implement handoff protocol

### Active Plan State Tracking

See SKILL.md "Active Plan State" section for full rules. Key points:
- Active plan resolved per `.claude/rules/naming-and-paths.md`
- Reports go to `{plan-dir}/reports/`

## File Structure

### Overview Plan (plan.md)

**IMPORTANT:** All plan.md files MUST include YAML frontmatter. See `output-standards.md` for schema.

**Example plan.md structure:**
```markdown
---
title: "Feature Implementation Plan"
description: "Add user authentication with OAuth2 support"
status: pending
priority: P1
effort: 8h
issue: 123
branch: kai/feat/oauth-auth
tags: [auth, backend, security]
created: 2025-12-16
---

# Feature Implementation Plan

## Overview

Brief description of what this plan accomplishes.

## Phases

| # | Phase | Status | Effort | Link |
|---|-------|--------|--------|------|
| 1 | Setup | Pending | 2h | [phase-01](./phase-01-setup.md) |
| 2 | Implementation | Pending | 4h | [phase-02](./phase-02-impl.md) |
| 3 | Testing | Pending | 2h | [phase-03](./phase-03-test.md) |

## Dependencies

- List key dependencies here
```

**Guidelines:**
- Keep generic and under 80 lines
- List each phase with status/progress
- Link to detailed phase files
- Key dependencies

### Phase Files (phase-XX-name.md)
Fully respect the `.claude/rules/development-rules.md` file.
Each phase file should contain:

**Context Links**
- Links to related reports, files, documentation

**Overview**
- Priority
- Current status
- Brief description

**Key Insights**
- Important findings from research
- Critical considerations

**Requirements**
- Functional requirements
- Non-functional requirements

**Architecture**
- System design
- Component interactions
- Data flow

**Related Code Files**
- List of files to modify
- List of files to create
- List of files to delete

**Implementation Steps**
- Detailed, numbered steps
- Specific instructions

**Todo List**
- Checkbox list for tracking

**Success Criteria**
- Definition of done
- Validation methods

**Risk Assessment**
- Potential issues
- Mitigation strategies

**Security Considerations**
- Auth/authorization
- Data protection

**Next Steps**
- Dependencies
- Follow-up tasks
