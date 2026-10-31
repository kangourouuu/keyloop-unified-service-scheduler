# Phase 01 — Project skeleton

## Context Links
- Plan: [plan.md](./plan.md) · Research: [researcher-01](./research/researcher-01-spring-boot-4-stack.md) (versions, pom, pitfalls)
- Conventions: `/Users/kangourouuu/orca/workspaces/keyloop-unified-service-scheduler/tilefish/docs/code-standards.md`, `.claude/rules/java-conventions.md`
- C-017 #8, #9

## Overview
- Priority: P1 · Status: Pending · Effort: 3h · Story: KUSS-04 · Commit: `[KUSS-04] Add Spring Boot 4 project skeleton`
- Buildable Boot 4.0.9 app with wrapper, Checkstyle gate, Surefire/Failsafe split, Testcontainers base, compose + image. No domain code.

## Key Insights
- Bare `flyway-core` silently skips migrations in Boot 4 → `spring-boot-starter-flyway` + `flyway-database-postgresql`.
- Testcontainers 2: per-tech modules (`testcontainers-postgresql`), class `org.testcontainers.postgresql.PostgreSQLContainer`.
- `@Bean @ServiceConnection` container in one `TestcontainersConfiguration` → one container per cached context.
- Checkstyle plugin 3.6.0 bundles Checkstyle 9.3, which cannot parse Java 21 pattern matching → pin an exact Checkstyle core version as plugin dependency (latest 10.x/11.x on Maven Central at implementation time, written as a literal version, never a range). <!-- Updated: Red Team #14 -->
- Decision 1 needs JPA for reads → add `spring-boot-starter-data-jpa` (research pom omitted it); it brings JDBC/`JdbcClient`.
- Container runtime is **Podman** (Podman Desktop, machine `podman-machine-default`, libkrun), not Docker; no `docker` CLI. The orchestrator provisions JDK 21 (Homebrew) and starts the machine before `/implement`; the implementer installs nothing. Wrapper comes from start.spring.io (no global Maven). <!-- Updated: Validation Session 1 - Podman -->
- Testcontainers on Podman needs, in the shell that runs `./mvnw`: `DOCKER_HOST=unix://<podman machine API socket>` (from `podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}'`), `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`, and Ryuk handled: `TESTCONTAINERS_RYUK_CONTAINER_PRIVILEGED=true` on a rootful machine, else `TESTCONTAINERS_RYUK_DISABLED=true`.
- Podman resolves short image names strictly → every image is fully qualified: `docker.io/library/postgres:17`, `docker.io/library/eclipse-temurin:21-jdk` / `21-jre` (Dockerfile, compose, `TestcontainersConfiguration`).

## Requirements
- Functional: app starts against Postgres; `Clock` bean (`Clock.systemUTC()`) injectable; `podman compose up --build` and `docker compose up --build` both run db + app (one compose file, standard Compose spec only).
- Non-functional: `./mvnw verify` runs Checkstyle (fails on method > 20 lines in main sources), `*Test` via Surefire, `*IT` via Failsafe.

## Architecture
- Package root `com.keyloop.scheduler`; feature packages added later: `common` (error, web, util), `catalog`, `availability`, `appointment` (incl. idempotency), `audit`.
- Image build: **multi-stage Dockerfile** — `podman compose up --build` / `docker compose up --build` is one command for reviewers; `spring-boot:build-image` needs a separate step + builder pull.
- Profiles: default (local, human logs) · `prod` (ECS JSON console logs, Hikari pool 20 / connection-timeout 3s) · `test` (bigger Hikari pool).
- Postgres runs with `deadlock_timeout=100ms` in compose AND Testcontainers: concurrent conflicting EXCLUDE inserts can deadlock by design (phase 05), so the loser must abort fast. <!-- Updated: Red Team #1, #4 -->

## Related Code Files
Create (root `/Users/kangourouuu/orca/workspaces/keyloop-unified-service-scheduler/tilefish`):
- `/Users/kangourouuu/orca/workspaces/keyloop-unified-service-scheduler/tilefish/pom.xml`
- `/Users/kangourouuu/orca/workspaces/keyloop-unified-service-scheduler/tilefish/mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`
- `/Users/kangourouuu/orca/workspaces/keyloop-unified-service-scheduler/tilefish/checkstyle.xml`
- `/Users/kangourouuu/orca/workspaces/keyloop-unified-service-scheduler/tilefish/.gitignore` (`target/`, `.idea/`, `*.iml`)
- `/Users/kangourouuu/orca/workspaces/keyloop-unified-service-scheduler/tilefish/Dockerfile`, `.dockerignore`
- `/Users/kangourouuu/orca/workspaces/keyloop-unified-service-scheduler/tilefish/docker-compose.yml`
- `/Users/kangourouuu/orca/workspaces/keyloop-unified-service-scheduler/tilefish/src/main/java/com/keyloop/scheduler/SchedulerApplication.java` (`@SpringBootApplication`, `@EnableScheduling`, `@Bean Clock clock()`)
- `/Users/kangourouuu/orca/workspaces/keyloop-unified-service-scheduler/tilefish/src/main/resources/application.yml`
- `/Users/kangourouuu/orca/workspaces/keyloop-unified-service-scheduler/tilefish/src/test/resources/application-test.yml`
- `/Users/kangourouuu/orca/workspaces/keyloop-unified-service-scheduler/tilefish/src/test/java/com/keyloop/scheduler/TestcontainersConfiguration.java`
- `/Users/kangourouuu/orca/workspaces/keyloop-unified-service-scheduler/tilefish/src/test/java/com/keyloop/scheduler/IntegrationTestSupport.java` (abstract base; not `*IT`)
- `/Users/kangourouuu/orca/workspaces/keyloop-unified-service-scheduler/tilefish/src/test/java/com/keyloop/scheduler/SchedulerApplicationIT.java`

## Implementation Steps
0. Prerequisites (provisioned by the orchestrator, verify only): `java -version` → 21; `podman machine list` shows `podman-machine-default` running; `DOCKER_HOST`, `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` and the Ryuk variable exported (Key Insights); `podman compose version` works. Missing → escalate, do not install. <!-- Updated: Validation Session 1 - Podman -->
1. Bootstrap: download a start.spring.io Maven zip into the scratchpad (`type=maven-project`, `bootVersion=4.0.9` or latest 4.0.x then edit parent, `javaVersion=21`, `groupId=com.keyloop`, `artifactId=unified-service-scheduler`, `packageName=com.keyloop.scheduler`); copy `mvnw`, `mvnw.cmd`, `.mvn/` into repo; `git update-index --chmod=+x mvnw` at commit time.
2. `pom.xml`: parent `spring-boot-starter-parent:4.0.9`; properties `java.version=21`, `springdoc.version=3.2.0`, `testcontainers.version=2.0.5` (drop the pin if `./mvnw dependency:tree` shows the Boot BOM manages it).
   - main: `-starter-webmvc`, `-starter-data-jpa`, `-starter-validation`, `-starter-actuator`, `-starter-flyway`, `flyway-database-postgresql`, `postgresql` (compile scope: `SqlErrorUtils` reads `PSQLException`), `micrometer-registry-prometheus`, `micrometer-tracing-bridge-brave`, `springdoc-openapi-starter-webmvc-ui`, `org.projectlombok:lombok` (provided), `org.mapstruct:mapstruct`. <!-- Updated: conventions 2026-10-31 -->
   - `maven-compiler-plugin` `annotationProcessorPaths` in this order: `lombok`, `mapstruct-processor`, `lombok-mapstruct-binding` (Lombok must run before MapStruct).
   - test: `-starter-webmvc-test`, `spring-boot-testcontainers`, `testcontainers-junit-jupiter`, `testcontainers-postgresql`, `org.awaitility:awaitility`.
   - plugins: `spring-boot-maven-plugin`; `maven-checkstyle-plugin:3.6.0` with `checkstyle` core dependency override, `configLocation=checkstyle.xml`, `consoleOutput`, `failOnViolation=true`, `includeTestSourceDirectory=false`, execution `check` bound to `validate`; `maven-failsafe-plugin` goals `integration-test` + `verify`.
3. `checkstyle.xml`: `Checker > TreeWalker > MethodLength max=20 countEmpty=true` (default severity error).
4. **Prove the gate (do not commit):** add temp `src/main/java/com/keyloop/scheduler/CheckstyleProbe.java` with one method of 25 statement lines → `./mvnw -q validate` must FAIL citing `MethodLength`; delete file → `./mvnw -q validate` passes.
5. Write failing test first: `SchedulerApplicationIT extends IntegrationTestSupport` — `contextLoads()` asserts `Clock` bean exists and `SELECT 1` via `JdbcClient` returns 1. Run `./mvnw verify` → fails (no app yet).
6. `TestcontainersConfiguration` (`@TestConfiguration(proxyBeanMethods = false)`, `@Bean @ServiceConnection PostgreSQLContainer postgres()` with `docker.io/library/postgres:17` and `.withCommand("postgres", "-c", "deadlock_timeout=100ms")`). <!-- Updated: Red Team #1 -->
   `IntegrationTestSupport`: `@SpringBootTest(webEnvironment = RANDOM_PORT)`, `@Import(TestcontainersConfiguration.class)`, `@ActiveProfiles("test")`, `@LocalServerPort int port`. Identical annotations on every IT → one cached context, one container.
7. `SchedulerApplication` with `@EnableScheduling` and `@Bean Clock clock() { return Clock.systemUTC(); }`. Every "now" in later phases = `clock.instant()`.
8. `application.yml`:
   ```yaml
   spring:
     application.name: unified-service-scheduler
     threads.virtual.enabled: true
     datasource: { url: jdbc:postgresql://localhost:5432/kuss, username: kuss, password: kuss }
     jpa: { open-in-view: false, generate-ddl: false, hibernate.ddl-auto: validate }
     flyway: { clean-disabled: true, validate-on-migrate: true, baseline-on-migrate: false }
   ---
   spring.config.activate.on-profile: prod
   logging.structured.format.console: ecs
   spring.datasource.hikari: { maximum-pool-size: 20, connection-timeout: 3000 }   # Red Team #4: fail fast, never queue unbounded
   ```
   `application-test.yml`: `spring.datasource.hikari.maximum-pool-size: 30` (N=20 burst + audit + holder connection).
9. `Dockerfile`: stage 1 `docker.io/library/eclipse-temurin:21-jdk` → copy wrapper, pom, `checkstyle.xml`, src → `./mvnw -q -DskipTests package`; stage 2 `docker.io/library/eclipse-temurin:21-jre` → `RUN useradd --system --uid 10001 app` → copy `target/*.jar` → `USER app` → `ENTRYPOINT ["java","-jar","/app/app.jar"]` (non-root, Red Team #14). `.dockerignore`: `target`, `.git`, `plans`, `docs`.
10. `docker-compose.yml`: `db` = `docker.io/library/postgres:17` (env `POSTGRES_DB/USER/PASSWORD=kuss`, `command: ["postgres", "-c", "deadlock_timeout=100ms"]`, healthcheck `pg_isready -h localhost -U kuss -d kuss` (TCP, so it fails during the init-script phase), port `127.0.0.1:5432:5432`) <!-- Updated: Red Team #1, #14 -->; `app` = `build: .`, env `SPRING_PROFILES_ACTIVE=prod`, `SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/kuss`, port 8080, `restart: on-failure`, `depends_on: db: condition: service_healthy`. No Redis/RabbitMQ.
11. `./mvnw verify` green; `podman compose up --build` → app log `Started SchedulerApplication`; `podman compose down -v`. The README (KUSS-06) states that `docker compose` works the same way.

## Todo List
- [ ] Prerequisites verified (JDK 21, Podman machine, Testcontainers env)
- [ ] Wrapper from start.spring.io, executable bit
- [ ] pom with pinned versions + exact Checkstyle core override
- [ ] checkstyle.xml + probe proves failure, probe removed
- [ ] `SchedulerApplicationIT` written first, then app + Clock bean
- [ ] application.yml (+prod), application-test.yml
- [ ] TestcontainersConfiguration + IntegrationTestSupport
- [ ] Dockerfile (non-root), .dockerignore, docker-compose.yml (deadlock_timeout, TCP healthcheck, restart, 127.0.0.1), .gitignore
- [ ] `./mvnw verify` green (on Podman), `podman compose` smoke run

## Success Criteria
- `./mvnw verify` → BUILD SUCCESS; Failsafe report lists `SchedulerApplicationIT` passed.
- Probe step: `./mvnw -q validate` fails with `MethodLength` while probe exists, passes after removal.
- `podman compose up --build` starts both containers; `curl -s localhost:8080/actuator/health` → `{"status":"UP"...}`.

## Risk Assessment
- start.spring.io may no longer list 4.0.9 → generate latest 4.0.x, set parent to 4.0.9 manually.
- BOM may not manage TC 2 / flyway-database-postgresql / brave → pin explicitly (check `dependency:tree`).
- Podman machine not running or env vars missing → all `*IT` fail with "Could not find a valid Docker environment"; escalate to the orchestrator (step 0). Ryuk failing to start → switch to `TESTCONTAINERS_RYUK_DISABLED=true`.
- `podman compose` delegates to an external provider (`docker-compose` or `podman-compose`); `depends_on: condition: service_healthy` needs the `docker-compose` provider or podman-compose ≥ 1.0.6.

## Security Considerations
- Dev-only DB creds (`kuss/kuss`) inline in yml/compose; no `.env` files (git hook blocks them). Real deployments override via env.
- Postgres published on `127.0.0.1` only (dev creds never reachable from the LAN); app container runs as non-root.

## Next Steps
- Phase 02 adds Flyway migrations; `ddl-auto: validate` starts checking entities there.
