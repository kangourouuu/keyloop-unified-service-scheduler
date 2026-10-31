# KUSS-02 Research: Spring Boot 4 Stack (2026-10-30)

Legend: **[V]** verified (sources at bottom). **[K]** knowledge, verify in Phase 01.

## Versions
- **[V]** Boot latest 4.0.x = **4.0.9**. Newer GA minor 4.1.2. Pre-release 4.2.0-RC1.
- **[V]** springdoc **3.2.0** (3.x = Boot 4; never mix with 2.x). **[V]** checkstyle plugin **3.6.0**. **[V]** Testcontainers **2.0.5**.
- **[K]** Postgres `postgres:17` (design says 16+).

## 1. Boot 4 stack
- **[V]** Parent `spring-boot-starter-parent:4.0.9`, Java 21, Spring Framework 7.
- **[V]** Starters: `-webmvc` (not `-web`), `-jdbc`, `-validation`, `-actuator`, `-flyway`. No JPA (pitfall 3).
- **[V]** Flyway auto-config lives in module `spring-boot-flyway`. Bare `flyway-core` no longer triggers it: **migrations silently don't run**. Use `spring-boot-starter-flyway`.
- **[K]** Postgres also needs `org.flywaydb:flyway-database-postgresql` (Flyway 10+). Missing = startup failure.

## 2. Testcontainers
- **[V]** `spring-boot-testcontainers` + `org.testcontainers:testcontainers-junit-jupiter` and `testcontainers-postgresql`, both 2.0.5. Each tech is its own `testcontainers-<tech>` module in 2.x.
- **[V]** `@ServiceConnection` on `PostgreSQLContainer` auto-wires JDBC. Prefer `@Bean @ServiceConnection` in a shared `TestcontainersConfiguration`: JUnit-managed containers can stop before cached contexts finish.
- **[K]** Import `org.testcontainers.postgresql.PostgreSQLContainer`. `btree_gist` is in the official image and is trusted (PG13+).

## 3. springdoc
- **[V]** `org.springdoc:springdoc-openapi-starter-webmvc-ui:3.2.0` (not Boot-managed, pin). **[K]** UI `/swagger-ui.html`, spec `/v3/api-docs`.

## 4. Observability
- **[K]** Prometheus: `io.micrometer:micrometer-registry-prometheus`; expose `prometheus`.
- **[K]** Tracing with no exporter: `io.micrometer:micrometer-tracing-bridge-brave`. No zipkin reporter means no network and no errors, yet `traceId`/`spanId` reach MDC. Skip `spring-boot-starter-opentelemetry` (exports to localhost:4318 by default and logs failures).
- **[V]** Boot 4 splits tracing into modules. If `traceId` is absent, add `spring-boot-micrometer-tracing-brave`.
- **[K]** Structured logs: `logging.structured.format.console=ecs` (or `logstash`). MDC included.

## 5. Jackson 3
- **[V]** `com.fasterxml.jackson` → `tools.jackson` (e.g. `tools.jackson.databind.json.JsonMapper`). Annotations stay `com.fasterxml.jackson.annotation`.
- **[V]** An `ObjectMapper` bean no longer overrides auto-config. Define a `JsonMapper` bean.
- **[K]** Exceptions are unchecked (`tools.jackson.core.JacksonException`).
- **JSONB (simplest):** pass JSON as `String` via `JdbcClient`, cast in SQL: `CAST(:body AS jsonb)`. Use `CAST`, not `::`, next to named params.

## 6. Virtual threads and async
- **[K]** `spring.threads.virtual.enabled=true` turns Boot's `applicationTaskExecutor` into an unbounded virtual-thread `SimpleAsyncTaskExecutor`. **Yes, it changes default `@Async`.**
- **[K]** Defining ANY `Executor` bean makes Boot back off its executor, so un-qualified `@Async` silently uses your pool. Always write `@Async("auditExecutor")`.
- **[K]** `@EnableAsync` / `@EnableScheduling` are not auto-enabled. `@Scheduled` pool defaults to 1.
- Bounded audit executor (in a `@Configuration` with `@EnableAsync`):

```java
@Bean(name = "auditExecutor")
ThreadPoolTaskExecutor auditExecutor() {
    var ex = new ThreadPoolTaskExecutor();
    ex.setCorePoolSize(2);
    ex.setMaxPoolSize(4);
    ex.setQueueCapacity(1000);
    ex.setThreadNamePrefix("audit-");
    ex.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
    return ex;
}
```
  `DiscardPolicy` drops silently. Ponytail: accept, or subclass to WARN/count (3 lines).
- **[K]** Hikari default pool = 10. Set `spring.datasource.hikari.maximum-pool-size` explicitly for the N-parallel IT.

## 7. Checkstyle and test split
- **[K]** `check` binds `verify` by default: rebind to `validate`. `includeTestSourceDirectory` defaults `false`.
- **[K]** `countEmpty` defaults `true`: blanks and comments count toward 20. Keep default (strict).
- **[K]** XML default severity `error` already fails the build. `violationSeverity=warning` only matters for warning-severity configs.
- **[K]** Surefire (`*Test`) and Failsafe (`*IT`) versions come from the Boot parent. Don't pin.

`checkstyle.xml` (repo root):

```xml
<?xml version="1.0"?>
<!DOCTYPE module PUBLIC "-//Checkstyle//DTD Checkstyle Configuration 1.3//EN" "https://checkstyle.org/dtds/configuration_1_3.dtd">
<module name="Checker">
  <module name="TreeWalker">
    <module name="MethodLength">
      <property name="max" value="20"/>
      <property name="countEmpty" value="true"/>
    </module>
  </module>
</module>
```

## 8. Docker (simplest)
- **[K]** Zero Dockerfile: `./mvnw spring-boot:build-image` (Buildpacks; needs Docker daemon and builder pull). Image `unified-service-scheduler:0.0.1-SNAPSHOT`.
- Compose: `db` = `postgres:17` + `pg_isready` healthcheck; `app` = build-image tag, `depends_on: service_healthy`, `SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/kuss`. Dev creds inline, no `.env`.

## pom.xml (versions from parent; only springdoc and Testcontainers pinned)

```xml
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>4.0.9</version>
  <relativePath/>
</parent>
<properties>
  <java.version>21</java.version>
  <springdoc.version>3.2.0</springdoc.version>
  <testcontainers.version>2.0.5</testcontainers.version>
</properties>
<dependencies>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-webmvc</artifactId></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-jdbc</artifactId></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
  <dependency><groupId>io.micrometer</groupId><artifactId>micrometer-registry-prometheus</artifactId></dependency>
  <dependency><groupId>io.micrometer</groupId><artifactId>micrometer-tracing-bridge-brave</artifactId></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-flyway</artifactId></dependency>
  <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-database-postgresql</artifactId></dependency>
  <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><scope>runtime</scope></dependency>
  <dependency><groupId>org.springdoc</groupId><artifactId>springdoc-openapi-starter-webmvc-ui</artifactId><version>${springdoc.version}</version></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-webmvc-test</artifactId><scope>test</scope></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-testcontainers</artifactId><scope>test</scope></dependency>
  <dependency><groupId>org.testcontainers</groupId><artifactId>testcontainers-junit-jupiter</artifactId><version>${testcontainers.version}</version><scope>test</scope></dependency>
  <dependency><groupId>org.testcontainers</groupId><artifactId>testcontainers-postgresql</artifactId><version>${testcontainers.version}</version><scope>test</scope></dependency>
</dependencies>
<build>
  <plugins>
    <plugin>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-maven-plugin</artifactId>
    </plugin>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-checkstyle-plugin</artifactId>
      <version>3.6.0</version>
      <configuration>
        <configLocation>checkstyle.xml</configLocation>
        <consoleOutput>true</consoleOutput>
        <includeTestSourceDirectory>false</includeTestSourceDirectory>
      </configuration>
      <executions>
        <execution>
          <id>checkstyle-check</id>
          <phase>validate</phase>
          <goals><goal>check</goal></goals>
        </execution>
      </executions>
    </plugin>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-failsafe-plugin</artifactId>
      <executions>
        <execution>
          <goals>
            <goal>integration-test</goal>
            <goal>verify</goal>
          </goals>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

Omitted: `spring-boot-starter-data-jpa`. Confirm with `dependency:tree` that Boot manages `testcontainers`, `flyway-database-postgresql`, `micrometer-tracing-bridge-brave`.

## Pitfalls
1. `flyway-core` alone = no migrations, no error. Check log for `Successfully applied`.
2. JPA wraps `23P01` in `DataIntegrityViolationException`; `JdbcClient` exposes SQLState and constraint name.
3. Jackson 3 `com.fasterxml` databind imports fail to compile; `ObjectMapper` bean ignored.
4. Testcontainers 2: `postgresql` artifact alone is not enough; package path moved.
5. springdoc 2.x on Boot 4 fails at startup.
6. OTel starter logs export errors without collector.
7. `DiscardPolicy` loses audit silently; an `Executor` bean hijacks un-qualified `@Async`.
8. `./mvnw test` skips `*IT`. Use `verify`.
9. No `mvnw` in repo. Bootstrap via start.spring.io or one-time `mvn -N wrapper:wrapper`, then `git update-index --chmod=+x mvnw`.

## Unresolved questions
1. Boot line: 4.0.9 (requested) vs 4.1.2 GA. Decide before Phase 01. 4.0 OSS end-of-support not checked.
2. Boot 4 property to disable OTLP export; confirm Brave path needs none.
3. Does Boot 4.0.9 BOM manage testcontainers, flyway-database-postgresql, micrometer-tracing-bridge-brave?
4. `org.testcontainers.postgresql.PostgreSQLContainer` import path in 2.0.5.
5. Checkstyle core version bundled with plugin 3.6.0.
6. `@Scheduled` on virtual threads by default in Boot 4.0.9?
7. JPA needed? Design §5 does not mandate it.
8. Web budget: 6 lookups (target ~5). All [K] items unverified.

## Sources
- Boot 4.0 migration guide: https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide
- Boot 4.0 Testcontainers: https://docs.spring.io/spring-boot/4.0/reference/testing/testcontainers.html
- Versions (Maven Central metadata): spring-boot-starter-parent, springdoc-openapi-starter-webmvc-api, maven-checkstyle-plugin (`repo1.maven.org/maven2/.../maven-metadata.xml`)
