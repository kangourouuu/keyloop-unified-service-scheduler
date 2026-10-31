# Naming & Paths

Single source for every path/name rule. Skills and agents that mention "naming pattern" or "active plan" mean this file.

## Git

- Branch: `feature/KUSS-XX-short-description` (kebab-case, `XX` = story number)
- Commit: `[KUSS-XX] Description` — imperative, no AI references
- Story id `KUSS-XX` is taken from the current branch name; if branch has none, ask the user.

## Plans

- Plan dir: `plans/{YYMMDD}-KUSS-XX-{slug}/` (date via `date +%y%m%d`)
- Inside: `plan.md` (overview + phase table) and `phase-NN-{slug}.md` per phase
- Reports: `{plan-dir}/reports/{agent}-{YYMMDD-HHmm}-{slug}.md`
- No plan for the task → reports go to `plans/reports/`

## Active plan

1. Plan path passed as argument → use it.
2. Else the newest `plans/*-KUSS-XX-*` dir matching the current branch's story id.
3. Else none → create a new plan dir (planning) or ask the user for the path (implement/validate/red-team).

## Flyway migrations

- Path: `src/main/resources/db/migration/`
- Name: `V<version>__<description>.sql`. The version is a sequential integer (`V1`, `V2`, …), followed by two underscores. The description is lower snake_case and starts with a verb, e.g. `V1__create_schema.sql`, `V2__insert_seed_data.sql`, `V3__add_index_appointment_start_time.sql`.
- Never edit a committed migration; add the next version. Full rule: `docs/code-standards.md` §4.

## Design docs

- Brainstorm drafts: `docs/brainstorm/`
- System design / architecture: `docs/system-design.md`
- Code standards: `docs/code-standards.md`
