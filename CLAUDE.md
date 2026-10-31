# CLAUDE.md

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

---

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.

## Project: Keyloop Unified Service Scheduler

Keyloop challenge, Scenario A — backend only: REST API + PostgreSQL for resource-constrained service booking (bay + qualified technician for the whole duration). Java 21, Spring Boot 4, PostgreSQL, Docker Compose (Redis, RabbitMQ, WebSocket: roadmap only). Approved design: `docs/brainstorm/first-draft-high-level.md`; Java conventions: `docs/code-standards.md`. Challenge brief: `KeyloopCodingChallange.pdf`.

- Verify: build + tests through the project wrapper (`./mvnw verify` or `./gradlew build`, whichever exists). Integration tests need Docker (Testcontainers).
- Git hooks (once per clone): `git config core.hooksPath .githooks` — enforces branch `feature/KUSS-XX-short-description`, commit `[KUSS-XX] Description`, no `.env` files.

### Workflow (gate with `AskUserQuestion` before moving to the next step)

1. **Brainstorm** — `superpowers:brainstorming` → design in `docs/brainstorm/`.
2. **Plan** — `/plan-story <story>` → `plans/{YYMMDD}-KUSS-XX-{slug}/plan.md` (overview) + `phase-NN-*.md`.
3. **Validate plan** — `/plan-story red-team {plan-dir}`, then `/plan-story validate {plan-dir}`.
4. **Implement** — `/implement {plan-dir}/plan.md`.
5. **Code review** — `/review-code`; fix Critical/High, re-verify.
6. **Split for PR** — one story per branch off `main`. If a branch carries more than one story, split it (new `feature/KUSS-XX-*` branch per story, cherry-pick its commits) before opening the PR.

### Always

- **Ponytail** (`ponytail:ponytail`) for every coding task — laziest solution that actually works.
- **RTK for shell** per `RTK.md`; `rtk proxy <cmd>` only when condensed output is unusable.
- **Native search**: Glob/Grep/Read for targeted lookups, `Explore` subagent for broad sweeps.
- **Evidence before claims**: run build + tests before saying done (`superpowers:verification-before-completion`).
- **Cross-agent discussion** with Antigravity: append to `docs/agent-discussion/claude-to-antigravity.md`, read replies in `antigravity-to-claude.md` (protocol in each file header). Never write to the other agent's file.

Workflow, naming and development rules live in `.claude/rules/`; component rules there are scoped with `paths:` frontmatter and load only when matching files are touched. Where they conflict with the guidelines above, the project rules win.
