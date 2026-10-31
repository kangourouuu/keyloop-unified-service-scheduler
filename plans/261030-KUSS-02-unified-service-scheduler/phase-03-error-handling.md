# Phase 03 — Error handling

## Context Links
- Plan: [plan.md](./plan.md) · Draft §8.1 (ProblemDetail format + KUSS_* catalog), §7.4 step 2 (requestId)
- Decision 7 (plan brief): one enum + one exception, single `*GlobalHandler`, request-id filter

## Overview
- Priority: P1 · Status: Pending · Effort: 2h · Story: KUSS-04 · Commit: `[KUSS-04] Add KUSS error catalog, global handler and request id`
- Every non-2xx leaves as `application/problem+json` with top-level `code` + `requestId`; requestId in MDC, `X-Request-Id`, request attribute.

## Key Insights
- One `ErrorCode` enum (constant name = wire code, e.g. `KUSS_NO_CAPACITY`) + one `SchedulerException(ErrorCode, String detail)`. No exception class per code.
- Spring MVC built-ins (405, 406, 415, `NoResourceFoundException`, missing params) implement `ErrorResponse` → keep their status, attach a KUSS code. Otherwise a catch-all would turn 405 into 500.
- `@ExceptionHandler` cannot target the `ErrorResponse` interface → catch-all `Exception` handler checks `instanceof ErrorResponse`.
- Spring 7 Jackson 3 mixin flattens `ProblemDetail.properties` → assert `$.code`, not `$.properties.code`.
- Merge request-id into one filter: `RequestAuditFilter` starts here (id only) and gains audit dispatch in phase 07 — one try/finally, one class.

## Requirements
- Functional mappings:

| Exception | Status | Code |
|---|---|---|
| `SchedulerException` | `code.status()` | its code |
| `MissingRequestHeaderException` for `Idempotency-Key` | 400 | `KUSS_IDEMPOTENCY_KEY_MISSING` |
| `MethodArgumentNotValidException`, `HandlerMethodValidationException`, `ConstraintViolationException` | 400 | `KUSS_VALIDATION_FAILED` (detail = `field: message` list) |
| `HttpMessageNotReadableException`, `TypeMismatchException` | 400 | `KUSS_VALIDATION_FAILED`, fixed detail `"Malformed request."` (Jackson/conversion messages carry Java types) |
| other `ErrorResponse` (405, 415, missing param, …) | its status + its `getHeaders()` copied (e.g. `Allow`) | 404 → `KUSS_RESOURCE_NOT_FOUND`, else `KUSS_VALIDATION_FAILED`; fixed detail per status (`"Resource not found."`, `"Method not allowed."`, `"Invalid request."`) |
| anything else | 500 | `KUSS_INTERNAL_ERROR`, fixed detail, `log.error` with stack |

- Non-functional: no stack trace, class name or SQL in `detail`; every 4xx detail is a fixed string or our own `SchedulerException` text (field names + constraint messages for validation lists); handler sets request attribute `kuss_error_code`. <!-- Updated: Red Team #9 -->

## Architecture
```java
public enum ErrorCode {
    KUSS_VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    KUSS_IDEMPOTENCY_KEY_MISSING(HttpStatus.BAD_REQUEST),
    KUSS_RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND),
    KUSS_VEHICLE_ALREADY_BOOKED(HttpStatus.CONFLICT),
    KUSS_NO_CAPACITY(HttpStatus.CONFLICT),
    KUSS_IDEMPOTENCY_IN_FLIGHT(HttpStatus.CONFLICT),
    KUSS_APPOINTMENT_ALREADY_STARTED(HttpStatus.CONFLICT),
    KUSS_IDEMPOTENCY_KEY_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT), // 422; use UNPROCESSABLE_ENTITY if the constant is absent
    KUSS_INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);
    // ctor + status() getter
}
```
Single builder used by every handler method:
```java
private ProblemDetail problem(HttpStatusCode status, ErrorCode code, String detail, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setProperty("code", code.name());
    problem.setProperty("requestId", request.getAttribute(RequestConstants.REQUEST_ID_ATTRIBUTE));
    request.setAttribute(RequestAuditFilter.ERROR_CODE_ATTRIBUTE, code.name());
    return problem;
}
```
`RequestAuditFilter extends OncePerRequestFilter`, `@Component`, `@Order(Ordered.HIGHEST_PRECEDENCE)`: `requestId = UUID.randomUUID()` (never trust an inbound header), `MDC.put("requestId", …)`, `response.setHeader(HeaderConstants.REQUEST_ID, …)`, `request.setAttribute(REQUEST_ID_ATTRIBUTE, …)`; `finally MDC.remove("requestId")`. Attribute keys live in `RequestConstants` (`REQUEST_ID_ATTRIBUTE = "kuss_request_id"`, `ERROR_CODE_ATTRIBUTE = "kuss_error_code"`), because the filter, the handler and the audit writer all read them. <!-- Updated: constants decision 2026-10-31 -->

## Related Code Files
`{root}` = `/Users/kangourouuu/orca/workspaces/keyloop-unified-service-scheduler/tilefish`. Create:
- `{root}/src/main/java/com/keyloop/scheduler/common/error/ErrorCode.java`
- `{root}/src/main/java/com/keyloop/scheduler/common/error/SchedulerException.java`
- `{root}/src/main/java/com/keyloop/scheduler/common/error/SchedulerGlobalHandler.java`
- `{root}/src/main/java/com/keyloop/scheduler/common/web/RequestAuditFilter.java`
- `{root}/src/main/java/com/keyloop/scheduler/common/constants/ErrorMessageConstants.java`: every fixed `detail` text (`"Malformed request."`, `"Invalid request."`, `"Resource not found."`, `"Method not allowed."`, `"Unexpected error. Quote the requestId when reporting."`; later phases add `BOOKING_NOT_COMPLETED`, `APPOINTMENT_NOT_FOUND`, `APPOINTMENT_ALREADY_STARTED`) <!-- Updated: constants decision 2026-10-31 -->
- `{root}/src/main/java/com/keyloop/scheduler/common/constants/HeaderConstants.java`: `IDEMPOTENCY_KEY = "Idempotency-Key"`, `REQUEST_ID = "X-Request-Id"`
- `{root}/src/main/java/com/keyloop/scheduler/common/constants/RequestConstants.java`: request attribute and MDC keys
- `{root}/src/test/java/com/keyloop/scheduler/common/error/SchedulerGlobalHandlerTest.java` (unit, MockMvc standalone)
- `{root}/src/test/java/com/keyloop/scheduler/common/error/FailingTestController.java` (test-only, `/api/test/boom`; picked up by component scan in every IT context — intended, reused by `RequestAuditIT`)
- `{root}/src/test/java/com/keyloop/scheduler/common/error/ErrorHandlingIT.java`
Modify:
- `{root}/src/test/java/com/keyloop/scheduler/IntegrationTestSupport.java`: add `RestClient http` (`baseUrl("http://localhost:" + port)`, `defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {})` so 4xx/5xx don't throw) and a `JsonMapper` (`tools.jackson`) for assertions.

## Implementation Steps
1. Tests first (red): `SchedulerGlobalHandlerTest` — `MockMvcBuilders.standaloneSetup(new StubController()).setControllerAdvice(new SchedulerGlobalHandler())`, nested test `StubController` + `StubDto(@NotNull UUID id)`; every request `.requestAttr(REQUEST_ID_ATTRIBUTE, "req-1")`.
   - parameterized over all `ErrorCode` values via `SchedulerException` → status = `code.status()`, `$.code`, `$.requestId == "req-1"`, `Content-Type: application/problem+json`
   - missing `Idempotency-Key` → 400 `KUSS_IDEMPOTENCY_KEY_MISSING`
   - `{}` body → 400 `KUSS_VALIDATION_FAILED`, detail contains `id`
   - `{bad json` → 400 `KUSS_VALIDATION_FAILED`; `?n=abc` on an `int` param → 400
   - POST on GET-only path → 405 with `KUSS_VALIDATION_FAILED` and an `Allow: GET` header
   - `ConstraintViolationException` thrown by the stub → 400 `KUSS_VALIDATION_FAILED`
   - every 4xx case above: `detail` contains none of `java.`, `com.keyloop`, `tools.jackson`
   - `IllegalStateException("SELECT * FROM secret")` → 500 `KUSS_INTERNAL_ERROR`, detail contains neither `SELECT` nor `IllegalStateException`
   - request attribute `kuss_error_code` set on the `MvcResult` request
2. `ErrorHandlingIT extends IntegrationTestSupport` (red): `GET /api/test/boom` → 500, `$.code == KUSS_INTERNAL_ERROR`, `$.requestId` equals `X-Request-Id` header, detail sanitized; `GET /api/v1/does-not-exist` → 404 `KUSS_RESOURCE_NOT_FOUND`; `GET /actuator/health` carries `X-Request-Id`.
3. Implement `ErrorCode`, `SchedulerException` (`code()` accessor; message = client-safe detail only).
4. Implement `SchedulerGlobalHandler` (`@RestControllerAdvice`): one method per row of the mapping table, all ≤ 20 lines, all delegating to `problem(...)`. 500 path: `log.error("Unhandled error requestId={}", requestId, ex)`, detail `"Unexpected error. Quote the requestId when reporting."`.
5. Implement `RequestAuditFilter` (id only for now).
6. `./mvnw verify` green.

## Todo List
- [ ] `SchedulerGlobalHandlerTest` + `ErrorHandlingIT` red
- [ ] `ErrorCode`, `SchedulerException`
- [ ] `SchedulerGlobalHandler` with `problem(...)` builder
- [ ] `RequestAuditFilter` (requestId, MDC, header, attribute)
- [ ] `FailingTestController`, `IntegrationTestSupport` RestClient
- [ ] `./mvnw verify` green

## Success Criteria
- `./mvnw verify` green; `SchedulerGlobalHandlerTest` (Surefire) and `ErrorHandlingIT` (Failsafe) pass.
- `curl -i localhost:8080/api/v1/nope` → `404`, `application/problem+json`, body has `code` + `requestId`, header `X-Request-Id`.

## Risk Assessment
- `HandlerMethodValidationException` only fires if the controller has constraint annotations on params; fine, covered once used in phase 06.
- If Jackson 3 mixin does not flatten properties, fall back to a `ProblemDetail` subclass with `@JsonAnyGetter`; test catches it.

## Security Considerations
- Detail sanitization: only `SchedulerException` messages (authored by us) and fixed strings reach clients; Spring/Jackson exception messages never do.
- requestId always server-generated (no log injection via inbound header).

## Next Steps
- Phase 04 throws `SchedulerException(KUSS_RESOURCE_NOT_FOUND / KUSS_VALIDATION_FAILED)`; phase 07 adds audit dispatch to `RequestAuditFilter`.
