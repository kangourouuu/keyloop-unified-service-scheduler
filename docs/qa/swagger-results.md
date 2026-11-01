# Swagger UI Test Results: Unified Service Scheduler (Scenario A)

- **Run time:** 2026-11-01 15:47 - 15:55 UTC
- **DATE used:** `2026-11-08` (`date -v+7d +%F`)
- **Git SHA:** `f780067` (`feature/KUSS-04-unified-service-scheduler`)
- **Server:** `http://localhost:8080`, Swagger UI at `http://localhost:8080/swagger-ui.html` (`http://localhost:8080/swagger-ui/index.html`)
- **Execution mode:** Driven entirely in Google Chrome via browser automation (`agent-browser`), clicking "Try it out" and "Execute" per step; no curl/API calls used in place of Swagger steps.
- **Database resets:** Executed via `podman exec kuss04-bugfix-db-1 psql -U kuss -d kuss -c "TRUNCATE appointment, idempotency_key;"` before SW-5 and SW-9 as instructed.

---

## Summary

| Total | PASS | FAIL | BLOCKED |
|:---:|:---:|:---:|:---:|
| 10 | 9 | 0 | 1 |

---

## Detailed Step Results (SW-1 to SW-10)

| Step | Case | Status | Result | Key Fields / Details Observed | One-Line Evidence |
|---|---|---|---|---|---|
| **SW-1** | DH-01 | 302 -> 200 | **PASS** | Address bar changed to `http://localhost:8080/swagger-ui/index.html`; page header "Unified Service Scheduler API v1 OAS 3.1"; all 4 controllers (appointment, catalog, availability, scheduler-error) and 16 operations displayed. | Redirected to `http://localhost:8080/swagger-ui/index.html` (302 -> 200) and Swagger UI fully loaded with all controller endpoints. |
| **SW-2** | CT-01 | 200 | **PASS** | 2 items returned: `London Central` (`Europe/London`, 08:00:00-18:00:00) and `New York Midtown` (`America/New_York`, 08:00:00-17:00:00). | HTTP 200 returned 2 dealerships with matching names, timezones, and operating hours. |
| **SW-3** | CT-02 | 200 | **PASS** | 4 bays (BAY-01..03 `isActive: true`, BAY-04 `isActive: false`); 5 technicians (`isActive: true`, skillCodes as in §0.3); zero fields named `active`. | HTTP 200 returned 4 bays (BAY-04 inactive), 5 technicians with correct skills, and only `isActive` properties. |
| **SW-4** | AV-01 | 200 | **PASS** | `timezone: "Europe/London"`; 20 slots; first slot `2026-11-08T08:00:00Z` (`availableCapacity: 3`); last slot `2026-11-08T17:30:00Z` (`availableCapacity: 3`). | HTTP 200 returned 20 slots for 2026-11-08 spanning 08:00:00Z to 17:30:00Z, with first and last capacities equal to 3. |
| **SW-5** | BK-01 | 201 | **PASS** | `id: "4305a581-444c-49f1-b88d-1651c14012e3"` (ID_BK01); `status: "CONFIRMED"`; `startTime: "2026-11-08T10:00:00Z"`; `endTime: "2026-11-08T10:30:00Z"`; `serviceTypeCode: "OIL_CHANGE"`; `vehicleId: "...0005-000000000001"`; `customerId: "...0004-000000000001"`; `serviceBayId: "...0002-000000000103"`; `technicianId: "...0003-000000000103"`; `cancelledAt: null`; `createdAt: "2026-11-01T15:50:08.328266Z"`. Displayed in UI as `201 Undocumented`. | HTTP 201 returned confirmed booking ID_BK01=`4305a581-444c-49f1-b88d-1651c14012e3` with valid bay and technician assigned. |
| **SW-6** | ID-01 | None (Client error) | **BLOCKED** | Swagger UI client-side validation rejected submission: `"Please correct the following validation errors and try again. For 'Idempotency-Key': Required field is not provided."` Input field highlighted with `class="invalid"`. No HTTP request dispatched to server; no HTTP status code returned. DB query verified 0 new appointments created. | Swagger UI client-side validation prevents execution when required header `Idempotency-Key` is empty, blocking network transmission of the invalid request. |
| **SW-7** | RD-01 | 200 | **PASS** | `id: "4305a581-444c-49f1-b88d-1651c14012e3"` (ID_BK01); `status: "CONFIRMED"`; `startTime: "2026-11-08T10:00:00Z"`; `endTime: "2026-11-08T10:30:00Z"`; `serviceTypeCode: "OIL_CHANGE"`; bay `...103`; tech `...103`; all fields identical to SW-5. | HTTP 200 returned ID_BK01 with appointment details exactly matching SW-5 creation payload. |
| **SW-8** | CN-01 | 200 | **PASS** | `id: "4305a581-444c-49f1-b88d-1651c14012e3"`; `status: "CANCELLED"`; `cancelledAt: "2026-11-01T15:52:55.369769Z"` (non-null timestamp). | HTTP 200 returned ID_BK01 with status updated to CANCELLED and non-null cancelledAt timestamp. |
| **SW-9** | DH-09 | 200, 201, 200, 200, 200 | **PASS** | 1. Availability: 200, slot `2026-11-08T11:30:00Z` has `availableCapacity: 3` (CAP_BEFORE=3).<br>2. Book: 201, `id: "44dfd3f0-082e-41ed-91da-427b4cff76a9"`, `status: "CONFIRMED"`, vehicle 23, customer 2, bay `...102`, tech `...101`.<br>3. Read: 200, fields match step 2.<br>4. Cancel: 200, `status: "CANCELLED"`, `cancelledAt: "2026-11-01T15:54:37.633706Z"`.<br>5. Availability again: 200, slot `2026-11-08T11:30:00Z` has `availableCapacity: 3` (= CAP_BEFORE). | Complete 5-step lifecycle executed in Swagger UI; returned status sequence (200, 201, 200, 200, 200) and restored 11:30 capacity to 3 after cancellation. |
| **SW-10** | M3, DH-02 | Doc check | **PASS** | Responses section under `POST /api/v1/appointments` documents only HTTP `200` (`OK`, returning `AppointmentDto`). Real success code HTTP 201 is omitted from docs and appears as `201 Undocumented` when executed. Error responses (400, 404, 409, 422, etc.) are completely omitted from OpenAPI specification (`/v3/api-docs`). | Verified in Swagger UI Responses table and `/v3/api-docs` that only HTTP 200 is documented for POST /api/v1/appointments (confirming mismatch M3). |

---

## Analysis of BLOCKED Step: SW-6

- **Test expectation:** HTTP 400 with `code=KUSS_IDEMPOTENCY_KEY_MISSING`, `requestId` present, no appointment created.
- **Root cause of BLOCKED:** The OpenAPI specification (`/v3/api-docs`) defines parameter `Idempotency-Key` as `in: "header"`, `required: true`. Swagger UI strictly enforces client-side form validation before dispatching requests. When the `Idempotency-Key` input field is left empty, Swagger UI intercepts the "Execute" button click, displays the error message `"Please correct the following validation errors and try again. For 'Idempotency-Key': Required field is not provided."`, and suppresses the network request entirely.
- **Verification:** As no network request was sent, no HTTP status or server response body was generated in the browser. Database inspection confirmed that 0 appointments were created. While the backend server correctly implements HTTP 400 `KUSS_IDEMPOTENCY_KEY_MISSING` when tested via curl (case ID-01), the test cannot be driven through Swagger UI due to Swagger UI's client-side validation barrier.


---

## SW-6 re-run after Idempotency-Key optional in OpenAPI (commit 9dd9e76)

- **Run time:** 2026-11-01 16:14 UTC (2026-11-01 23:14 +07:00)
- **DATE used:** `2026-11-08` (`date -v+7d +%F`)
- **Git SHA:** `9dd9e76` (`feature/KUSS-04-unified-service-scheduler`)
- **Execution mode:** Driven entirely in Google Chrome via browser automation (`agent-browser`), clicking "Try it out", leaving `Idempotency-Key` empty, pasting the request body with `DATE=2026-11-08`, and clicking "Execute". No curl or API calls were used in place of the Swagger step. No database reset was performed.
- **Result:** **PASS**

### Observed Values
- **HTTP Status:** `400` (`400 Undocumented`)
- **Response Code:** `KUSS_IDEMPOTENCY_KEY_MISSING`
- **Response Detail:** `"Idempotency-Key header is required."`
- **Request ID:** `717a9230-cc43-4543-b24d-381beba8d8dd`
- **Response Body:**
  ```json
  {
    "detail": "Idempotency-Key header is required.",
    "instance": "/api/v1/appointments",
    "status": 400,
    "title": "Bad Request",
    "code": "KUSS_IDEMPOTENCY_KEY_MISSING",
    "requestId": "717a9230-cc43-4543-b24d-381beba8d8dd"
  }
  ```
- **Database Verification:** Verified database appointment count remained unchanged at 1; no new appointment was created.

### Verification Summary
Following commit `9dd9e76`, `Idempotency-Key` is marked optional in the OpenAPI document (`required: false`), so Swagger UI no longer blocks execution with client-side validation errors. The request was dispatched to the server without an `Idempotency-Key` header, and the backend returned HTTP 400 Bad Request with code `KUSS_IDEMPOTENCY_KEY_MISSING`, detail `"Idempotency-Key header is required."`, and a valid `requestId`.
