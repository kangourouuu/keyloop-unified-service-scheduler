# Codebase Understanding Phase

**When to skip:** If provided with exploration reports, skip this phase.

## Core Activities

### Native Explore Subagents
- Targeted lookup (known class/file) → Glob/Grep/Read directly
- Broad sweep → spawn native `Explore` subagents in parallel, one per area (e.g. booking domain, persistence, API/error handling)
- Wait for all to report back before analysis
- Efficient for finding relevant code across large codebases

### Essential Documentation Review
ALWAYS read these files first:

1. **`.claude/rules/development-rules.md`** (IMPORTANT)
   - File Name Conventions
   - File Size Management
   - Development rules and best practices
   - Code quality standards
   - Security guidelines

2. **`./docs/system-design.md`**
   - Architecture, components, data model + `EXCLUDE` constraints
   - Data flow (availability, booking retry loop, cancel, audit), API surface, `KUSS_*` error catalog
   - Open `TODO(design)` items the plan must resolve

3. **`./docs/code-standards.md`** (agent checklist: `.claude/rules/java-conventions.md`)
   - Class suffixes, records for DTOs, methods ≤ 20 lines, `*Test` vs `*IT`

4. **`./docs/brainstorm/first-draft-high-level.md`**
   - Approved design, developer decisions and mandatory test scenarios (§12), roadmap-only items (§10)

5. **`./docs/codebase-summary.md`** (if exists)
   - Current module map

### Environment Analysis
- `pom.xml` / `build.gradle` (whichever exists): dependencies, Surefire/Failsafe, Checkstyle
- `docker-compose.yml`, `application*.yml`: Postgres config, profiles
- Flyway migrations: current schema and seed data

### Pattern Recognition
- Study existing patterns in codebase
- Identify conventions and architectural decisions
- Note consistency in implementation approaches
- Understand error handling patterns

### Integration Planning
- Identify how new features integrate with existing architecture
- Map dependencies between components
- Understand data flow and transaction boundaries
- Consider backward compatibility

## Best Practices

- Start with documentation before diving into code
- Use Explore subagents for broad discovery, Glob/Grep for targeted lookups
- Document patterns found for consistency
- Note any inconsistencies or technical debt
- Consider impact on existing features
