# Phase 04 — Availability check + catalog reads

## Context Links
- Plan: [plan.md](./plan.md) · Draft §7.1 (sweep), §8 (endpoints), §11 #4–#5 (grid, hours)
- C-017 #5 (sweep, back-to-back, unqualified/other dealership, DST), #7 (time contract)

## Overview
- Priority: P1 · Status: Pending · Effort: 5h <!-- Updated: Red Team #6 --> · Story: KUSS-04 · Commits: `[KUSS-04] Add time slot and availability sweep logic`, `[KUSS-04] Add availability and catalog read endpoints`
- Pure time/sweep logic (unit-tested) + read-only services + controllers for availability and catalog discovery.

## Key Insights
- All "now" = `clock.instant()` (injected `Clock`), so past-slot filtering is testable with a fixed instant.
- Grid/hours checked in the dealership `ZoneId`, computed per local date → DST days shift UTC instants correctly.
- Overlap test must equal the constraint: `[)` → `a.start < end && start < a.end`.
- Partial index `WHERE status = 'CONFIRMED'` is only usable if the status is a SQL literal, not a bind param → JPQL uses the FQN enum literal `com.keyloop.scheduler.appointment.AppointmentStatus.CONFIRMED` (Hibernate inlines enum literals).
- `TimeSlotUtils` is shared with booking validation (phase 05) → put it in `common.util`. Booking validity = `candidateStarts(...).contains(start)`: one source of truth for grid, hours, past and DST (no separate `isOnGrid`/`isWithinHours` that could disagree). <!-- Updated: Red Team #13 -->
- Date horizon: dates > dealership-local today + 90 days → 400 (unbounded `LocalDate` reaches timestamptz limits → 500 + log spam; also caps calendar squatting). <!-- Updated: Red Team #13 -->

## Requirements
- `GET /api/v1/dealerships/{id}/availability?date=YYYY-MM-DD&serviceType=CODE` →
  `{dealershipId, date, serviceTypeCode, timezone, slots:[{startTime, availableCapacity}]}`; `startTime` = ISO-8601 with the dealership's local offset (`OffsetDateTime`); only slots with capacity ≥ 1; past starts (before now) dropped; past date → empty `slots`. <!-- Superseded: developer decision 2026-10-31 (F-7, review) -->
- **Developer decision (after review, 2026-10-31):** availability accepts only today .. today + 90 days in the dealership zone; a past date → 400 `KUSS_VALIDATION_FAILED` — overrides "past date → empty `slots`". Reason: availability for the past is meaningless, and far dates fell outside `timestamptz` and returned 500.
- Errors: malformed/missing `date`, `date` > today + 90 days, or missing `serviceType` → 400 `KUSS_VALIDATION_FAILED`; unknown dealership or unknown/inactive service type → 404 `KUSS_RESOURCE_NOT_FOUND`.
- Catalog: `GET /api/v1/dealerships`, `GET /api/v1/dealerships/{id}` (with bays + technicians + skills; 404), `GET /api/v1/service-types` (active only), `GET /api/v1/customers` (vehicles as `{id, make, model, modelYear}`; no email/phone/VIN/plate). <!-- Updated: Red Team #10 -->
- Non-functional: one indexed appointment read + two id reads per availability call, no N+1; services `@Transactional(readOnly = true)`.

## Architecture
`TimeSlotUtils` (final, private ctor, static, each ≤ 20 lines):
- `List<Instant> candidateStarts(LocalDate date, ZoneId zone, LocalTime open, LocalTime close, int durationMinutes, Instant now)` — from `date.atTime(open).atZone(zone)` step 30 min while `start + duration ≤ close instant`; drop `start < now`.
- `boolean isWithinHorizon(LocalDate date, ZoneId zone, Instant now)` — `!date.isAfter(LocalDate.ofInstant(now, zone).plusDays(MAX_DAYS_AHEAD))`, `MAX_DAYS_AHEAD = 90`. Used by availability and booking.
- No `isOnGrid` / `isWithinHours`: booking checks `candidateStarts(LocalDate.ofInstant(start, zone), …).contains(start)`.
- `Window localDay(LocalDate date, ZoneId zone)` / `Window openHours(...)` with nested `record Window(Instant start, Instant end)`.

`AvailabilitySweepUtils.sweep(List<Instant> starts, Duration duration, ZoneId zone, Set<UUID> bayIds, Set<UUID> technicianIds, List<BusyIntervalDto> busy)` → `List<SlotDto>`:
```java
private static int capacityAt(Instant start, Instant end, Set<UUID> bayIds, Set<UUID> technicianIds, List<BusyIntervalDto> busy) {
    Set<UUID> busyBays = new HashSet<>();
    Set<UUID> busyTechnicians = new HashSet<>();
    busy.stream()
        .filter(b -> b.startTime().isBefore(end) && start.isBefore(b.endTime()))   // same [) rule as the EXCLUDE
        .forEach(b -> { busyBays.add(b.serviceBayId()); busyTechnicians.add(b.technicianId()); });
    long freeBays = bayIds.stream().filter(id -> !busyBays.contains(id)).count();
    long freeTechnicians = technicianIds.stream().filter(id -> !busyTechnicians.contains(id)).count();
    return (int) Math.min(freeBays, freeTechnicians);
}
```
Busy ids outside the given sets (unqualified tech, other dealership) never reduce capacity by construction.

`AvailabilityServiceImpl.getAvailability(dealershipId, date, serviceTypeCode)`: load dealership (404) + active service type (404) → `candidateStarts` → `serviceBayRepository.findActiveIds` + `technicianRepository.findQualifiedActiveIds` → `appointmentRepository.findConfirmedOverlapping(dealershipId, openHours.start, openHours.end)` → sweep. Empty bays or techs → empty slots.

Repository queries (JPQL):
- `ServiceBayRepository`: `select b.id from ServiceBayEntity b where b.dealershipId = :dealershipId and b.active = true`; `findByDealershipIdOrderByBayCode`.
- `TechnicianRepository`: `select t.id from TechnicianEntity t join t.skillCodes s where t.dealershipId = :dealershipId and t.active = true and s = :serviceTypeCode`; `findByDealershipIdOrderByFullName`.
- `AppointmentRepository.findConfirmedOverlapping`: `… a.status = <FQN>.CONFIRMED and a.startTime < :to and a.endTime > :from`.

## Related Code Files
`{root}` = `/Users/kangourouuu/orca/workspaces/keyloop-unified-service-scheduler/tilefish`. Create:
- `{root}/src/main/java/com/keyloop/scheduler/common/util/TimeSlotUtils.java`
- `{root}/src/main/java/com/keyloop/scheduler/availability/{AvailabilitySweepUtils,AvailabilityService,AvailabilityServiceImpl,AvailabilityController}.java`
- `{root}/src/main/java/com/keyloop/scheduler/availability/{AvailabilityDto,SlotDto,BusyIntervalDto}.java` + MapStruct `AvailabilityMapper` (`toBusyInterval(AppointmentEntity)`; no mapping inside DTOs) <!-- Updated: conventions 2026-10-31 -->
- `{root}/src/main/java/com/keyloop/scheduler/catalog/{CatalogService,CatalogServiceImpl,CatalogController}.java`
- `{root}/src/main/java/com/keyloop/scheduler/catalog/{DealershipDto,DealershipDetailDto,ServiceTypeDto,CustomerDto}.java` (bay/technician/vehicle as nested records with `from(...)`)
- `{root}/src/test/java/com/keyloop/scheduler/common/util/TimeSlotUtilsTest.java`
- `{root}/src/test/java/com/keyloop/scheduler/availability/AvailabilitySweepTest.java`
- `{root}/src/test/java/com/keyloop/scheduler/availability/AvailabilityApiIT.java`
- `{root}/src/test/java/com/keyloop/scheduler/catalog/CatalogApiIT.java`
Modify: `ServiceBayRepository`, `TechnicianRepository`, `AppointmentRepository` (queries above).

## Implementation Steps
1. Tests first (red) — `TimeSlotUtilsTest` (unit):
   - London 08:00–18:00, 60 min, winter date → 19 starts, first `08:00Z`, last `17:00Z`
   - DST: London 2026-03-29 first start `07:00Z` (BST); NY 2026-03-08 first `12:00Z` vs 2026-03-07 `13:00Z`
   - today with `now = 10:10Z` → first start `10:30Z`; past date → empty
   - booking check via `candidateStarts(...).contains(start)`: 09:15 local → false; 09:00:30 → false; `09:00+01:00` vs `08:00Z` same instant → same result; 120 min at 17:00 (close 18:00) → false; 16:00 → true; 07:30 → false; start + duration == close → true
   - horizon: today + 90 → true; today + 91 → false
2. `AvailabilitySweepTest` (unit): bay-bound (3 bays, 4 techs, no busy → 3); tech-bound (3 bays, 2 techs → 2); busy 09:00–10:00 on bay1+tech1 → 09:00 and 09:30 (60 min) reduced, 10:00 back-to-back not reduced; busy on a tech not in the qualified set reduces bays only; busy ids from another dealership → no effect; all bays busy → slot omitted.
3. `AvailabilityApiIT` (HTTP): London OIL_CHANGE date+7 → 20 slots, capacity 3; London slot `startTime` ends with `Z`/`+00:00` (GMT) or `+01:00` (BST) and NY slot ends with `-05:00`/`-04:00` (offset kept, not normalized to UTC; A-023 #3 covered by test, no Jackson config); `insertAppointment` on tech 101 09:00–10:30 → BRAKE_REPAIR 09:00 capacity 1; `date=2026-13-01` → 400; missing `serviceType` → 400; `serviceType=NOPE` → 404; unknown dealership → 404; past date → 200 empty; today + 91 → 400. <!-- Superseded: developer decision 2026-10-31 (F-7, review) -->
   `CatalogApiIT`: 4 lists return 200 with seed counts; London detail lists tech 101 with skills {OIL_CHANGE, TIRE_SERVICE, BRAKE_REPAIR} (proves the `skillCodes` mapping, replaces `SeedDataIT`); detail for unknown id → 404; customers payload vehicles have exactly `id, make, model, modelYear` (no `vin`, `licensePlate`, `email`).
4. Implement `TimeSlotUtils`, `AvailabilitySweepUtils` → unit tests green (`./mvnw test`).
5. Repository queries, `AvailabilityService`/`Impl`, `CatalogService`/`Impl`, DTO records, controllers (`@DateTimeFormat(iso = DATE) LocalDate date`, `@RequestParam("serviceType")`).
6. `./mvnw verify` green.

## Todo List
- [ ] `TimeSlotUtilsTest`, `AvailabilitySweepTest` red → green
- [ ] `AvailabilityApiIT`, `CatalogApiIT` red
- [ ] Repository queries (literal CONFIRMED)
- [ ] Availability service/controller + DTOs
- [ ] Catalog service/controller + DTOs
- [ ] `./mvnw verify` green

## Success Criteria
- `./mvnw verify` green with `TimeSlotUtilsTest`, `AvailabilitySweepTest`, `AvailabilityApiIT`, `CatalogApiIT` passing.
- `curl 'localhost:8080/api/v1/dealerships/00000000-0000-0000-0001-000000000001/availability?date=<future>&serviceType=OIL_CHANGE'` → slots with local offsets.

## Risk Assessment
- `OffsetDateTime` serialization: Jackson must keep the offset (not normalize to UTC) → asserted in `AvailabilityApiIT`.
- `GET /customers` is unbounded and unauthenticated → recorded in plan.md Out of scope (pagination + auth scoping when customers are real); no code comment. <!-- Updated: conventions 2026-10-31 -->
- Lazy `skillCodes` outside a tx → map to DTOs inside the `readOnly` service method (`open-in-view: false`).

## Security Considerations
- No auth: anyone can read all catalog data (auth + per-dealership scoping is roadmap). Customer names are exposed (synthetic seed); email, phone, VIN and plate are not. Inputs typed (`UUID`, `LocalDate`) → no injection surface; date horizon bounds the range.

## Next Steps
- Phase 05 reuses `TimeSlotUtils` for booking validation and the same overlap predicate in SQL.
