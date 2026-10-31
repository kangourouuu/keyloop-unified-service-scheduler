---
name: edge-case-exploration
description: Use after implementation, before code review to proactively find edge cases, side effects, and potential issues via native Explore subagent - catches problems code-reviewer might miss
---

# Edge Case Exploration

Proactive detection of edge cases, side effects, and potential issues before code review.

## Purpose

Code reviews catch obvious issues but miss subtle side effects. Exploration detects:
- Files affected by changes reviewer might not check
- Data flow paths that could break
- Boundary conditions and error paths
- Integration issues across modules

## When to Use

**Mandatory:** Multi-file features, shared utility refactors, complex bug fixes
**Optional:** Single-file changes, docs, config

## Process

### 1. Identify Changed Files
```bash
git diff --name-only HEAD~1
```

### 2. Spawn native Explore subagent
```
Agent(subagent_type: "Explore", description: "Explore edge cases", prompt: "
Explore edge cases for recent changes (medium breadth).

Changed: {files from git diff}

Find:
1. Files importing/depending on changed modules
2. Data flow paths through modified functions
3. Error handling paths not tested
4. Boundary conditions (null, empty, max)
5. Race conditions: concurrent bookings, `23P01` retry, in-flight idempotency keys
6. Transaction boundaries (rollback leaves no booking and no key), async audit (`REQUIRES_NEW`)
")
```

### 3. Analyze & Act

| Finding | Action |
|---------|--------|
| Affected file not in scope | Add to review |
| Data flow risk | Verify or add test |
| Edge case | Add test or verify |
| Missing test | Add before review |

### 4. Document for Review
```
Exploration findings:
- {issues found}
- Verified: {what checked}
- Addressed: {what fixed}
- Needs review: {remaining}
```

## Explore Prompts

**Feature:**
```
Explore edge cases for {feature}.
Changed: {files}
Find: consumers, error states, untested inputs, performance, compatibility
```

**Bug fix:**
```
Explore side effects of fix in {file}.
Bug: {description}, Fix: {approach}
Find: other paths using logic, dependent features, similar bugs
```

**Refactor:**
```
Explore breaking changes in {module}.
Before: {old}, After: {new}
Find: importers, behavior diffs, removed functionality
```

## What Exploration Catches

| Issue | Why Missed | Explore Detects |
|-------|------------|---------------|
| Indirect deps | Not in diff | Traces imports |
| Race conditions | Hard static review | Analyzes flow |
| State mutations | Hidden side effects | Tracks data |
| Missing null checks | Assumed safe | Boundary analysis |
| Integration breaks | Out of scope | Cross-module search |

## Red Flags

- Shared utility changed but only one caller tested
- Error path escapes the `*GlobalHandler` (raw 500, stack trace in body)
- State modified in place without notification
- Breaking change without migration

## Example

```
1. Done: Add cancel to AppointmentServiceImpl
2. Diff: AppointmentServiceImpl.java, AppointmentController.java
3. Explore: "edge cases for cancel"
4. Report:
   - Cancel after start time not rejected (needs 409 KUSS_APPOINTMENT_ALREADY_STARTED)
   - Already-cancelled appointment returns 409 instead of 200
   - Idempotency key not written in the cancel transaction
5. Fix: Add the checks + key write in the same transaction
6. Document for code-reviewer
```

## Bottom Line

Explore before review. Don't trust "simple changes" - explore them anyway.
