# Primary Workflow

**IMPORTANT:** Activate the skills needed for the task. Be token-efficient.

```
brainstorm → /plan-story → /plan-story red-team → /plan-story validate → /implement → /review-code → PR
```

1. **Brainstorm** — `superpowers:brainstorming`; output design to `docs/brainstorm/`.
2. **Plan** — `/plan-story <task>` (delegates to `planner`); phases + TODOs in the plan dir.
3. **Validate** — `/plan-story red-team {plan-dir}` then `/plan-story validate {plan-dir}`.
4. **Implement** — `/implement {plan-dir}/plan.md`; run the build after every file change.
5. **Test** — delegate to `tester`; 100% pass, no mocks/cheats to go green. Failures → `debugger`, fix, re-test.
6. **Review** — delegate to `code-reviewer` on tested code; fix critical/high before finalize.
7. **Finalize** — `project-manager` syncs plan status, `docs-manager` updates `./docs` if warranted, `git-manager` commits per `naming-and-paths.md`.

Follow the plan given by `planner`; keep API contracts; document breaking changes.
