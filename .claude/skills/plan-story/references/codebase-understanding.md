# Codebase Understanding Phase

**When to skip:** If provided with exploration reports, skip this phase.

## Core Activities

### Native Explore Subagents
- Targeted lookup (known class/file) → Glob/Grep/Read directly
- Broad sweep → spawn native `Explore` subagents in parallel, one per area (e.g. booking domain, persistence, messaging)
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

2. **`./docs/codebase-summary.md`**
   - Project structure and current status
   - High-level architecture overview
   - Component relationships

3. **`./docs/code-standards.md`**
   - Coding conventions and standards
   - Language-specific patterns
   - Naming conventions

4. **`./docs/system-design.md`** (if exists)
   - Design system guidelines

### Environment Analysis
- Review development environment setup
- Analyze dotenv files and configuration
- Identify required dependencies
- Understand build and deployment processes

### Pattern Recognition
- Study existing patterns in codebase
- Identify conventions and architectural decisions
- Note consistency in implementation approaches
- Understand error handling patterns

### Integration Planning
- Identify how new features integrate with existing architecture
- Map dependencies between components
- Understand data flow and state management
- Consider backward compatibility

## Best Practices

- Start with documentation before diving into code
- Use Explore subagents for broad discovery, Glob/Grep for targeted lookups
- Document patterns found for consistency
- Note any inconsistencies or technical debt
- Consider impact on existing features
