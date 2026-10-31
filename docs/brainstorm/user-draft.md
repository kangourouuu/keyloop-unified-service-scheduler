# Keyloop Unified Service Scheduler — user's draft (input to brainstorm)

Source challenge: `KeyloopCodingChallange.pdf` — Scenario A: The Unified Service Scheduler.

## Tickets

Each ticket has its own branch (`feature/KUSS-XX-*`), commits (`[KUSS-XX] ...`) and PR.

- KUSS-01: Setup project, setup small custom dir for agent (claude & antigravity)
- KUSS-02: Brainstorm first draft from initial draft of mine to get the first high-level specs
- KUSS-03: Turn specs to be visualization via answer-me-with-html and place on description
- KUSS-04: Implementation & Review (TDD)
- KUSS-05: Retest and report bugs
- KUSS-06: Documentation deliverables (system design doc, README + AI Collaboration Narrative, AI collaboration log, video outline)

## Steps
- [x] Clarify requirements and brainstorm about assumption
- [x] Discuss with chatbot to verify the requirement and assumption are enough or not. Revising if needed (first draft high-level)
- [x] Create project with setting up claude and antigravity [KUSS-01]
- [x] Put the first draft to brainstorm with antigravity one more time to get second draft high-level design (use gemini 3.8 flash with high effort)
- [ ] Create the plan from this draft [KUSS-02]
- [ ] Put this plan and second draft to claude code to cross-verify the plan (use opus 5.5 with high effort) - (use agent orchestration model to delegate task for claude and antigravity - make two model talks each other until get the actual plan) [KUSS-02]
- [ ] Review first draft plan
- [ ] Use answer with me html to create the html for final high level design from plan to get the general vision and easily review [KUSS-03]
- [ ] Review plan by human
- [ ] Implement [KUSS-04]
- [ ] Testing and reporting bugs [KUSS-05]

## Tech stacks
- Java (21) + Spring boot (4.0): familiar with Java 21 and Spring boot 4.0
- PostgreSQL: support JSONB with easier setup than oracle
- Docker
- Redis
- RabbitMQ (planning later)
- WebSocket (planning later because do not integrate UI)

## Step to work with agent
- Build main agent to delegate the task for corresponding agent
- Setup precommit hooks to validate commit and branch with format : feature/KUSS-XX-short-desrciption, commit [KUSS-XX] Description
- Setup CLAUDE.md (Andrej Karpathy format) for using ponytail, brainstorming to brainstorm, planning with overview plan and its phases, implement-story, validate plan, code review, split branch for PR
- Setup ponytail, brainstorming (superpowers)
- Setup convention for projects, system architecture high-level design
- Setup plan story, implement story, code reviewer agent and its references
- Cross-review plan with Gemini 3.8 flash
- Setup general workflow skill with specific steps and gate with AskUserQuestion hooks
- High-level brainstorm from assumptions and brainstorm and debate to get right direction

## Brainstorm
1. Resource Constrained Booking: Consider about race condition, Limit specific vehicle, service type with mapping enum, lock desired time to avoid same time with multiple booking
2. Real-Time Availability Check: WebSocket, Cache
3. Confirmed Appointment Record: Do not lock table, use atomic condition update for query at database level to update

## Assumptions
- Do not have real payment service (do not need to use RabbitMQ because we do not need to handle async payment, so use atomic condition update for non-blocking requests and keep data integrity for appointment booking)
- 500M requests / month for new booking record (WPS: ~192,9) => It's over the real car amount in the world, downgrade estimation to smaller point
- Peak concurrent traffics: 20k read ops, 2k write ops
- Response time: <= 100ms, accepted <= 1s (maximum)
- Booking threads do not block each other
- Each service & technician will have maximum X appointment (do not need to block because 1 service = 1 technician)
- Each pending booking will have X time window to block (do not need to block because 1 service = 1 technician in X duration to complete their work)
- Technician has corresponding service types as specialization
- Must have scalable instances
