# Manual test script: Unified Service Scheduler (Scenario A)

Requirements: `KeyloopCodingChallange.pdf` Scenario A (book for a vehicle + service type + dealership at a desired time; check a ServiceBay AND a qualified Technician for the whole duration; persist an Appointment with customer, vehicle, technician, bay). Contract: `README.md`, `docs/system-design.md` §4.7/§4.8, code under `src/main`. Server: `http://localhost:8080`. Swagger UI: `http://localhost:8080/swagger-ui.html`. OpenAPI: `/v3/api-docs`.

116 cases: Catalog 7, Availability 12, Booking 20, Idempotency 12, Conflicts 19, Vehicle 6, Cancel 11, Reads 8, Errors/headers 12, Docs/health 9. Run the sections in file order, once, after one RESET.

## 0. Fixed data (read first)

### 0.1 RESET (run before the suite; clears only bookings and idempotency keys, seed rows stay)

```sh
podman exec kuss04-bugfix-db-1 psql -U kuss -d kuss -c "TRUNCATE appointment, idempotency_key;"
```

Docker equivalent: `docker exec kuss04-bugfix-db-1 psql -U kuss -d kuss -c "TRUNCATE appointment, idempotency_key;"` (container name `kuss04-bugfix-db-1` confirmed with `podman ps`; check `docker ps` / `podman ps` if yours differs).

### 0.2 Fixed DATE

DATE = today + 7 days, computed once and printed at the start of the run. Swagger users: type the printed value wherever a case says `DATE`. Every booking case uses this DATE. The only cases that read the clock are the date-bound cases marked **[today-relative]** (past date, today, +90, +91, -365, -366); they cannot be expressed against a fixed date.

```sh
DATE=$(date -v+7d +%F 2>/dev/null || date -d '+7 days' +%F); echo "DATE=$DATE"
```

Times: London is `Z` (GMT), New York `-05:00` (EST). Both hold for DATE between 2026-11-08 and 2027-03-06. Outside that range, take the offset from an availability response.

### 0.3 Fixed seed (all verified in `src/main/resources/db/migration/V2__insert_seed_data.sql` with grep)

|Item|Id / code|Facts|
|---|---|---|
|London Central (LONDON)|`00000000-0000-0000-0001-000000000001`|Europe/London, 08:00-18:00|
|New York Midtown (NY)|`00000000-0000-0000-0001-000000000002`|America/New_York, 08:00-17:00|
|Bays London|`…0002-000000000101`, `…102`, `…103` active; `…104` inactive|BAY-01..BAY-04|
|Bays NY|`…0002-000000000201`, `…202`|BAY-01, BAY-02|
|Tech London 101|`00000000-0000-0000-0003-000000000101`|OIL_CHANGE, TIRE_SERVICE, BRAKE_REPAIR|
|Tech London 102|`…0003-000000000102`|OIL_CHANGE, TIRE_SERVICE|
|Tech London 103|`…0003-000000000103`|OIL_CHANGE, BRAKE_REPAIR|
|Tech London 104|`…0003-000000000104`|OIL_CHANGE|
|Tech London 105|`…0003-000000000105`|ENGINE_DIAGNOSTIC (only one)|
|Tech NY 201|`…0003-000000000201`|OIL_CHANGE, TIRE_SERVICE, BRAKE_REPAIR, ENGINE_DIAGNOSTIC|
|Tech NY 202|`…0003-000000000202`|OIL_CHANGE|
|OIL_CHANGE|30 min|London techs 101-104, NY techs 201, 202|
|TIRE_SERVICE|60 min|London techs 101, 102; NY tech 201|
|BRAKE_REPAIR|90 min|London techs 101, 103; NY tech 201|
|ENGINE_DIAGNOSTIC|120 min|London tech 105; NY tech 201|
|Customers|`00000000-0000-0000-0004-000000000001` .. `…003`|vehicle n belongs to customer ((n-1) mod 3)+1|
|Vehicles V01..V24|`00000000-0000-0000-0005-0000000000NN`, NN = 01..24|V01 = `00000000-0000-0000-0005-000000000001`; V02 = `…000000000002`; V24 = `…000000000024`|

Helper used in the blocks: `V 7` prints the id of vehicle V07.

### 0.4 Idempotency keys

Literal keys are given in each case (`case-bk-01`, `case-id-05`, ...). **Each key is used once per RESET** (some cases reuse a key on purpose; that is stated). After a second run without RESET, bookings answer 409 or replay old results. The 24 h expiry is not time dependent in these steps because they are executed immediately after RESET.

### 0.5 Swagger steps

Open the UI, expand the operation, "Try it out", type the path/query values from the curl line (DATE as printed above), for POST type the `Idempotency-Key` header and paste the JSON that the helper prints as `BODY ...`, "Execute", compare status, body and headers. Swagger shows `200` as the only documented response for the two POST operations (mismatch M3); judge by the real status. Cases that cannot run in Swagger say so.

### 0.6 Error body

All errors are `application/problem+json` with `status, title, detail, instance, code, requestId` and header `X-Request-Id` equal to `requestId`.

### 0.7 Section header block

Each section below starts with a block that defines `BASE`, `LONDON`, `NY`, `VEHICLE`, `DATE` and the helpers. Paste that block first, in the same shell, then any case of that section.

---

## 1. Catalog

Uses: LONDON, NY, VEHICLE (V01), seed table 0.3.

```sh
BASE=http://localhost:8080
LONDON=00000000-0000-0000-0001-000000000001
NY=00000000-0000-0000-0001-000000000002
VEHICLE=00000000-0000-0000-0005-000000000001
DATE=$(date -v+7d +%F 2>/dev/null || date -d '+7 days' +%F); echo "DATE=$DATE"
```

1. **CT-01 List dealerships** (Swagger: GET /api/v1/dealerships)
   ```sh
   curl -s $BASE/api/v1/dealerships | jq
   ```
   Expect 200; 2 items; keys `id,name,timezone,openTime,closeTime`; London Central `Europe/London` 08:00:00-18:00:00, New York Midtown `America/New_York` 08:00:00-17:00:00. Live: RAN, pass.
2. **CT-02 Dealership detail London, `isActive`** (Swagger: GET /api/v1/dealerships/{dealershipId})
   ```sh
   curl -s $BASE/api/v1/dealerships/$LONDON | jq '{bays:[.bays[]|{bayCode,isActive}],t:[.technicians[]|{fullName,isActive,skillCodes}],anyActive:([.bays[],.technicians[]]|any(has("active"))),allIsActive:([.bays[],.technicians[]]|all(has("isActive")))}'
   ```
   Expect 200; 4 bays (BAY-04 `isActive:false`, others true); 5 technicians with `skillCodes` as in 0.3; `anyActive` false, `allIsActive` true. Live: RAN, pass.
3. **CT-03 Dealership detail New York**
   ```sh
   curl -s $BASE/api/v1/dealerships/$NY | jq -c '{bays:[.bays[].bayCode],t:[.technicians[]|{n:.fullName,s:.skillCodes}]}'
   ```
   Expect 200; BAY-01, BAY-02; Tech 01 {BRAKE_REPAIR, ENGINE_DIAGNOSTIC, TIRE_SERVICE, OIL_CHANGE}, Tech 02 {OIL_CHANGE}. Live: RAN, pass.
4. **CT-04 Unknown dealership**
   ```sh
   curl -s -i $BASE/api/v1/dealerships/00000000-0000-0000-0001-000000000099
   ```
   Expect 404, `code=KUSS_RESOURCE_NOT_FOUND`, `detail="Dealership not found."`, `requestId` present. Live: RAN, pass.
5. **CT-05 Service types**
   ```sh
   curl -s $BASE/api/v1/service-types | jq -c '.[]'
   ```
   Expect 200; 4 items `{code,displayName,durationMinutes}`: BRAKE_REPAIR 90, ENGINE_DIAGNOSTIC 120, OIL_CHANGE 30, TIRE_SERVICE 60. Live: RAN, pass.
6. **CT-06 Customers**
   ```sh
   curl -s $BASE/api/v1/customers | jq -c '{n:length,v:[.[].vehicles|length],k:(.[0]|keys),vk:(.[0].vehicles[0]|keys)}'
   ```
   Expect 200; `n=3`, `v=[8,8,8]`, customer keys `["fullName","id","vehicles"]`, vehicle keys `["id","make","model","modelYear"]` (no VIN, plate, email). Live: RAN, pass.
7. **CT-07 Non-UUID id**
   ```sh
   curl -s -i $BASE/api/v1/dealerships/not-a-uuid
   ```
   Expect 400, `code=KUSS_VALIDATION_FAILED`, `detail="Malformed request."`. Live: RAN, pass.

## 2. Availability

Uses: LONDON, NY, DATE. Run right after RESET, before any booking: capacities below assume an empty calendar (AV-12 is the exception and is run right after BK-01). Slots with capacity 0 are omitted.

```sh
BASE=http://localhost:8080
LONDON=00000000-0000-0000-0001-000000000001
NY=00000000-0000-0000-0001-000000000002
VEHICLE=00000000-0000-0000-0005-000000000001
DATE=$(date -v+7d +%F 2>/dev/null || date -d '+7 days' +%F); echo "DATE=$DATE"
T() { date -v"$1"d +%F 2>/dev/null || date -d "$1 days" +%F; }
```

1. **AV-01 London OIL_CHANGE** (Swagger: GET …/availability, date=DATE, serviceType=OIL_CHANGE)
   ```sh
   curl -s "$BASE/api/v1/dealerships/$LONDON/availability?date=$DATE&serviceType=OIL_CHANGE" | jq -c '{timezone,n:(.slots|length),first:.slots[0],last:.slots[-1]}'
   ```
   Expect 200; `timezone=Europe/London`; `n=20`; first `{startTime:"DATET08:00:00Z",availableCapacity:3}`; last `DATET17:30:00Z` capacity 3 (BAY-04 inactive not counted). Live: RAN, pass.
2. **AV-02 NY TIRE_SERVICE**
   ```sh
   curl -s "$BASE/api/v1/dealerships/$NY/availability?date=$DATE&serviceType=TIRE_SERVICE" | jq -c '{timezone,n:(.slots|length),first:.slots[0],last:.slots[-1]}'
   ```
   Expect 200; `America/New_York`; `n=17`; first `DATET08:00:00-05:00`, last `DATET16:00:00-05:00`; capacity 1 (one qualified tech). Live: RAN, pass.
3. **AV-03 London ENGINE_DIAGNOSTIC**
   ```sh
   curl -s "$BASE/api/v1/dealerships/$LONDON/availability?date=$DATE&serviceType=ENGINE_DIAGNOSTIC" | jq -c '{n:(.slots|length),first:.slots[0],last:.slots[-1]}'
   ```
   Expect 200; `n=17`; last start `16:00:00Z` (ends exactly 18:00); capacity 1. Live: RAN, pass.
4. **AV-04 London BRAKE_REPAIR**
   ```sh
   curl -s "$BASE/api/v1/dealerships/$LONDON/availability?date=$DATE&serviceType=BRAKE_REPAIR" | jq -c '{n:(.slots|length),last:.slots[-1]}'
   ```
   Expect 200; `n=18`; last `16:30:00Z`; capacity 2. Live: RAN, pass.
5. **AV-05 London TIRE_SERVICE**
   ```sh
   curl -s "$BASE/api/v1/dealerships/$LONDON/availability?date=$DATE&serviceType=TIRE_SERVICE" | jq -c '{n:(.slots|length),last:.slots[-1]}'
   ```
   Expect 200; `n=19`; last `17:00:00Z`; capacity 2. Live: RAN, pass.
6. **AV-06 [today-relative] date = today**
   ```sh
   curl -s -w '\nHTTP %{http_code}\n' "$BASE/api/v1/dealerships/$LONDON/availability?date=$(T +0)&serviceType=OIL_CHANGE" | tail -2
   ```
   Expect 200; only slots later than now. Live: RAN, 200 (20 slots at 07:05Z).
7. **AV-07 [today-relative] yesterday**
   ```sh
   curl -s "$BASE/api/v1/dealerships/$LONDON/availability?date=$(T -1)&serviceType=OIL_CHANGE" | jq '{status,code,detail}'
   ```
   Expect 400, `KUSS_VALIDATION_FAILED`, `detail="Date is in the past."`. Live: RAN, pass.
8. **AV-08 [today-relative] horizon: today+90 ok, today+91 rejected**
   ```sh
   curl -s -o /dev/null -w '%{http_code}\n' "$BASE/api/v1/dealerships/$LONDON/availability?date=$(T +90)&serviceType=OIL_CHANGE"
   curl -s "$BASE/api/v1/dealerships/$LONDON/availability?date=$(T +91)&serviceType=OIL_CHANGE" | jq '{status,code,detail}'
   ```
   Expect `200`, then 400 `KUSS_VALIDATION_FAILED` `detail="Date is more than 90 days ahead."`. Live: RAN, pass.
9. **AV-09 Missing and malformed `date`**
   ```sh
   curl -s "$BASE/api/v1/dealerships/$LONDON/availability?serviceType=OIL_CHANGE" | jq '{status,code,detail}'
   curl -s "$BASE/api/v1/dealerships/$LONDON/availability?date=2026-13-45&serviceType=OIL_CHANGE" | jq '{status,code,detail}'
   ```
   Expect 400 `KUSS_VALIDATION_FAILED` twice (`Invalid request.` then `Malformed request.`). Live: RAN, pass.
10. **AV-10 `serviceType` missing, blank, 41 chars**
    ```sh
    curl -s "$BASE/api/v1/dealerships/$LONDON/availability?date=$DATE" | jq '{status,code}'
    curl -s "$BASE/api/v1/dealerships/$LONDON/availability?date=$DATE&serviceType=" | jq '{status,code,detail}'
    curl -s "$BASE/api/v1/dealerships/$LONDON/availability?date=$DATE&serviceType=$(printf 'X%.0s' $(seq 41))" | jq '{status,code,detail}'
    ```
    Expect 400 `KUSS_VALIDATION_FAILED` x3; blank detail `serviceType: must not be blank`; long detail `serviceType: size must be between 0 and 40`. Live: RAN, pass.
11. **AV-11 Unknown service type, unknown dealership**
    ```sh
    curl -s "$BASE/api/v1/dealerships/$LONDON/availability?date=$DATE&serviceType=NOPE" | jq '{status,code,detail}'
    curl -s "$BASE/api/v1/dealerships/00000000-0000-0000-0001-000000000099/availability?date=$DATE&serviceType=OIL_CHANGE" | jq '{status,code,detail}'
    ```
    Expect 404 `KUSS_RESOURCE_NOT_FOUND` x2 (`Service type not found.`, `Dealership not found.`). Live: RAN, pass.
12. **AV-12 Capacity falls only for the booked window** (run right after BK-01 booked V01 OIL 10:00Z-10:30Z)
    ```sh
    curl -s "$BASE/api/v1/dealerships/$LONDON/availability?date=$DATE&serviceType=OIL_CHANGE" | jq -c '.slots[]|select(.startTime|test("T(09:30|10:00|10:30):00Z"))'
    ```
    Expect 200; `09:30` capacity 3, `10:00` capacity 2, `10:30` capacity 3. Not run.

## 3. Booking

Uses: LONDON, NY, V01-V06, V12, DATE; keys `case-bk-01` .. `case-bk-20`. Calendar footprint on DATE: V01 OIL 10:00Z, V02 NY OIL 08:00-05:00, V03 OIL 17:30Z, V05 BRAKE 16:30Z.

```sh
BASE=http://localhost:8080
LONDON=00000000-0000-0000-0001-000000000001
NY=00000000-0000-0000-0001-000000000002
VEHICLE=00000000-0000-0000-0005-000000000001
DATE=$(date -v+7d +%F 2>/dev/null || date -d '+7 days' +%F); echo "DATE=$DATE"
T() { date -v"$1"d +%F 2>/dev/null || date -d "$1 days" +%F; }
V() { printf '00000000-0000-0000-0005-%012d' "$1"; }
bk() { B=$(jq -nc --arg d "$2" --arg v "$3" --arg s "$4" --arg t "$5" '{dealershipId:$d,vehicleId:$v,serviceTypeCode:$s,desiredStartTime:$t}'); echo "BODY $B"
  curl -s -i -X POST $BASE/api/v1/appointments -H 'Content-Type: application/json' -H "Idempotency-Key: $1" -d "$B" | tr -d '\r' | sed -n '1p;/^[Xx]-[Rr]equest-[Ii]d/p;/^{/p' | tee /tmp/kuss-last; }
J() { sed -n '/^{/p' /tmp/kuss-last | jq -r "$1"; }
```

`bk KEY DEALERSHIP VEHICLE SERVICE START` prints the JSON body it sends (for Swagger), the status line, `X-Request-Id` and the response JSON.

1. **BK-01 Happy path** (Swagger: POST /api/v1/appointments, header `Idempotency-Key: case-bk-01`)
   ```sh
   bk case-bk-01 $LONDON $(V 1) OIL_CHANGE ${DATE}T10:00:00Z; ID_BK01=$(J .id); echo $ID_BK01
   ```
   Expect 201; `status=CONFIRMED`, `startTime=DATET10:00:00Z`, `endTime=DATET10:30:00Z`, `customerId=00000000-0000-0000-0004-000000000001`, `vehicleId=V01`, `serviceBayId` in {…0002-000000000101..103}, `technicianId` in {…0003-000000000101..104}, `cancelledAt=null`, `createdAt` set, `serviceTypeCode=OIL_CHANGE`, header `X-Request-Id`. Raw curl form of the same call:
   `curl -s -i -X POST $BASE/api/v1/appointments -H 'Content-Type: application/json' -H 'Idempotency-Key: case-bk-01' -d "{\"dealershipId\":\"$LONDON\",\"vehicleId\":\"$(V 1)\",\"serviceTypeCode\":\"OIL_CHANGE\",\"desiredStartTime\":\"${DATE}T10:00:00Z\"}"` (do not run both; same key).
2. **BK-02 Offset input, New York opening boundary**
   ```sh
   bk case-bk-02 $NY $(V 2) OIL_CHANGE ${DATE}T08:00:00-05:00
   ```
   Expect 201; `startTime=DATET13:00:00Z`, `endTime=DATET13:30:00Z`, `customerId=…0004-000000000002`, bay in {…0002-000000000201, 202}, technician in {…0003-000000000201, 202}.
3. **BK-03 Last slot that fits (ends at closing)**
   ```sh
   bk case-bk-03 $LONDON $(V 3) OIL_CHANGE ${DATE}T17:30:00Z
   ```
   Expect 201; `endTime=DATET18:00:00Z`; `customerId=…0004-000000000003`.
4. **BK-04 Ends after closing**
   ```sh
   bk case-bk-04 $LONDON $(V 4) OIL_CHANGE ${DATE}T18:00:00Z
   ```
   Expect 400, `KUSS_VALIDATION_FAILED`, `detail="Start time is not a bookable slot."`.
5. **BK-05 90-minute service ending exactly at closing**
   ```sh
   bk case-bk-05 $LONDON $(V 5) BRAKE_REPAIR ${DATE}T16:30:00Z
   ```
   Expect 201; `endTime=DATET18:00:00Z`; technician in {…101, …103}.
6. **BK-06 90-minute service that would end after closing**
   ```sh
   bk case-bk-06 $LONDON $(V 4) BRAKE_REPAIR ${DATE}T17:00:00Z
   ```
   Expect 400, `KUSS_VALIDATION_FAILED` (whole duration must fit).
7. **BK-07 Off-grid start 10:15**
   ```sh
   bk case-bk-07 $LONDON $(V 4) OIL_CHANGE ${DATE}T10:15:00Z
   ```
   Expect 400, `KUSS_VALIDATION_FAILED`.
8. **BK-08 Before opening 07:30**
   ```sh
   bk case-bk-08 $LONDON $(V 4) OIL_CHANGE ${DATE}T07:30:00Z
   ```
   Expect 400, `KUSS_VALIDATION_FAILED`.
9. **BK-09 [today-relative] start in the past (yesterday 10:00Z)**
   ```sh
   bk case-bk-09 $LONDON $(V 4) OIL_CHANGE $(T -1)T10:00:00Z
   ```
   Expect 400, `KUSS_VALIDATION_FAILED`.
10. **BK-10 [today-relative] horizon boundary today+90**
    ```sh
    bk case-bk-10 $LONDON $(V 6) OIL_CHANGE $(T +90)T10:00:00Z
    ```
    Expect 201; `startTime` on today+90.
11. **BK-11 [today-relative] beyond horizon today+91**
    ```sh
    bk case-bk-11 $LONDON $(V 6) OIL_CHANGE $(T +91)T10:00:00Z
    ```
    Expect 400, `KUSS_VALIDATION_FAILED`, `detail="Date is more than 90 days ahead."`.
12. **BK-12 Unknown vehicle**
    ```sh
    bk case-bk-12 $LONDON 00000000-0000-0000-0005-000000000099 OIL_CHANGE ${DATE}T12:00:00Z
    ```
    Expect 404, `KUSS_RESOURCE_NOT_FOUND`, `detail="Vehicle not found."`.
13. **BK-13 Unknown dealership**
    ```sh
    bk case-bk-13 00000000-0000-0000-0001-000000000099 $(V 4) OIL_CHANGE ${DATE}T12:00:00Z
    ```
    Expect 404, `KUSS_RESOURCE_NOT_FOUND`, `detail="Dealership not found."`.
14. **BK-14 Unknown service type**
    ```sh
    bk case-bk-14 $LONDON $(V 4) NOPE ${DATE}T12:00:00Z
    ```
    Expect 404, `KUSS_RESOURCE_NOT_FOUND`, `detail="Service type not found."`.
15. **BK-15 Empty body**
    ```sh
    curl -s -i -X POST $BASE/api/v1/appointments -H 'Content-Type: application/json' -H 'Idempotency-Key: case-bk-15' -d '{}'
    ```
    Expect 400, `KUSS_VALIDATION_FAILED`; `detail` names `dealershipId`, `vehicleId`, `serviceTypeCode`, `desiredStartTime`.
16. **BK-16 Malformed JSON**
    ```sh
    curl -s -i -X POST $BASE/api/v1/appointments -H 'Content-Type: application/json' -H 'Idempotency-Key: case-bk-16' -d '{"dealershipId":'
    ```
    Expect 400, `KUSS_VALIDATION_FAILED`, `detail="Malformed request."`.
17. **BK-17 Start time without offset**
    ```sh
    curl -s -i -X POST $BASE/api/v1/appointments -H 'Content-Type: application/json' -H 'Idempotency-Key: case-bk-17' -d "{\"dealershipId\":\"$LONDON\",\"vehicleId\":\"$(V 4)\",\"serviceTypeCode\":\"OIL_CHANGE\",\"desiredStartTime\":\"${DATE}T12:00:00\"}"
    ```
    Expect 400, `KUSS_VALIDATION_FAILED` (expected from code, unverified; if it returns 201, record as a finding).
18. **BK-18 `serviceTypeCode` of 41 chars**
    ```sh
    bk case-bk-18 $LONDON $(V 4) $(printf 'X%.0s' $(seq 41)) ${DATE}T12:00:00Z
    ```
    Expect 400, `KUSS_VALIDATION_FAILED`, `detail="serviceTypeCode: size must be between 0 and 40"`.
19. **BK-19 Wrong content type** (not Swagger)
    ```sh
    curl -s -i -X POST $BASE/api/v1/appointments -H 'Content-Type: text/plain' -H 'Idempotency-Key: case-bk-19' -d x
    ```
    Expect 415, `code=KUSS_VALIDATION_FAILED`. Live: RAN, 415 (no side effect).
20. **BK-20 `vehicleId` not a UUID**
    ```sh
    curl -s -i -X POST $BASE/api/v1/appointments -H 'Content-Type: application/json' -H 'Idempotency-Key: case-bk-20' -d "{\"dealershipId\":\"$LONDON\",\"vehicleId\":\"abc\",\"serviceTypeCode\":\"OIL_CHANGE\",\"desiredStartTime\":\"${DATE}T12:00:00Z\"}"
    ```
    Expect 400, `KUSS_VALIDATION_FAILED`, `detail="Malformed request."`.

## 4. Idempotency

Uses: LONDON, DATE, V07 (12:00Z), V08 (12:30Z), V09 (13:00Z), V10 (13:30Z), V11 (14:00Z), V12 (08:00Z); keys `case-id-NN` as listed. Same header block as section 3 plus `PSQL`.

```sh
BASE=http://localhost:8080
LONDON=00000000-0000-0000-0001-000000000001
NY=00000000-0000-0000-0001-000000000002
VEHICLE=00000000-0000-0000-0005-000000000001
DATE=$(date -v+7d +%F 2>/dev/null || date -d '+7 days' +%F); echo "DATE=$DATE"
PSQL="podman exec kuss04-bugfix-db-1 psql -U kuss -d kuss"
V() { printf '00000000-0000-0000-0005-%012d' "$1"; }
bk() { B=$(jq -nc --arg d "$2" --arg v "$3" --arg s "$4" --arg t "$5" '{dealershipId:$d,vehicleId:$v,serviceTypeCode:$s,desiredStartTime:$t}'); echo "BODY $B"
  curl -s -i -X POST $BASE/api/v1/appointments -H 'Content-Type: application/json' -H "Idempotency-Key: $1" -d "$B" | tr -d '\r' | sed -n '1p;/^[Xx]-[Rr]equest-[Ii]d/p;/^{/p' | tee /tmp/kuss-last; }
cx() { curl -s -i -X POST $BASE/api/v1/appointments/$2/cancel -H "Idempotency-Key: $1" | tr -d '\r' | sed -n '1p;/^[Xx]-[Rr]equest-[Ii]d/p;/^{/p' | tee /tmp/kuss-last; }
J() { sed -n '/^{/p' /tmp/kuss-last | jq -r "$1"; }
```

1. **ID-01 Missing key**
   ```sh
   curl -s -i -X POST $BASE/api/v1/appointments -H 'Content-Type: application/json' -d "{\"dealershipId\":\"$LONDON\",\"vehicleId\":\"$(V 12)\",\"serviceTypeCode\":\"OIL_CHANGE\",\"desiredStartTime\":\"${DATE}T08:00:00Z\"}"
   ```
   Expect 400, `KUSS_IDEMPOTENCY_KEY_MISSING`, `requestId` present. (Swagger: clear the header field.)
2. **ID-02 Illegal characters in key**
   ```sh
   curl -s -i -X POST $BASE/api/v1/appointments -H 'Content-Type: application/json' -H 'Idempotency-Key: bad key!' -d "{\"dealershipId\":\"$LONDON\",\"vehicleId\":\"$(V 12)\",\"serviceTypeCode\":\"OIL_CHANGE\",\"desiredStartTime\":\"${DATE}T08:00:00Z\"}"
   ```
   Expect 400, `KUSS_VALIDATION_FAILED`; no appointment created.
3. **ID-03 Key of 129 chars**
   ```sh
   bk $(printf 'k%.0s' $(seq 129)) $LONDON $(V 12) OIL_CHANGE ${DATE}T08:00:00Z
   ```
   Expect 400, `KUSS_VALIDATION_FAILED`.
4. **ID-04 Key of exactly 128 chars**
   ```sh
   bk $(printf 'k%.0s' $(seq 128)) $LONDON $(V 12) OIL_CHANGE ${DATE}T08:00:00Z
   ```
   Expect 201 (V12 OIL 08:00Z-08:30Z).
5. **ID-05 Replay with the same key and body** (key `case-id-05`, used twice on purpose)
   ```sh
   bk case-id-05 $LONDON $(V 7) OIL_CHANGE ${DATE}T12:00:00Z; IDA=$(J .id)
   bk case-id-05 $LONDON $(V 7) OIL_CHANGE ${DATE}T12:00:00Z; [ "$(J .id)" = "$IDA" ] && echo SAME_ID
   curl -s "$BASE/api/v1/dealerships/$LONDON/appointments?date=$DATE" | jq --arg id "$IDA" '[.[]|select(.id==$id)]|length'
   ```
   Expect 201 twice with the same `id` and body, `SAME_ID` printed, and the day list count `1`.
6. **ID-06 Same key, different start time**
   ```sh
   bk case-id-05 $LONDON $(V 7) OIL_CHANGE ${DATE}T12:30:00Z
   ```
   Expect 422, `KUSS_IDEMPOTENCY_KEY_MISMATCH`; no new row.
7. **ID-07 Same key, different vehicle**
   ```sh
   bk case-id-05 $LONDON $(V 8) OIL_CHANGE ${DATE}T12:00:00Z
   ```
   Expect 422, `KUSS_IDEMPOTENCY_KEY_MISMATCH`.
8. **ID-08 Booking key reused on cancel**
   ```sh
   cx case-id-05 $IDA; curl -s $BASE/api/v1/appointments/$IDA | jq '{status}'
   ```
   Expect 422, `KUSS_IDEMPOTENCY_KEY_MISMATCH`; the GET shows `{"status":"CONFIRMED"}`.
9. **ID-09 A failed attempt stores no key** (key `case-id-09`, used twice on purpose)
   ```sh
   bk case-id-09 $LONDON 00000000-0000-0000-0005-000000000099 OIL_CHANGE ${DATE}T12:30:00Z
   bk case-id-09 $LONDON $(V 8) OIL_CHANGE ${DATE}T12:30:00Z
   ```
   Expect 404 `KUSS_RESOURCE_NOT_FOUND`, then 201 (not 422): the key was rolled back with the failed attempt.
10. **ID-10 IN_FLIGHT from an open claim (SQL fixture; not Swagger)**
    ```sh
    HASH=$(printf '%s' "POST /api/v1/appointments|$LONDON|$(V 9)|OIL_CHANGE|${DATE}T13:00:00Z" | shasum -a 256 | cut -d' ' -f1)
    $PSQL -c "insert into idempotency_key(idempotency_key,request_hash) values ('case-id-10','$HASH')"
    bk case-id-10 $LONDON $(V 9) OIL_CHANGE ${DATE}T13:00:00Z
    $PSQL -c "delete from idempotency_key where idempotency_key='case-id-10'"
    ```
    Expect 409, `KUSS_IDEMPOTENCY_IN_FLIGHT`. Not run (mutating).
11. **ID-11 Same key twice in parallel (best effort; not Swagger)**
    ```sh
    for i in 1 2; do bk case-id-11 $LONDON $(V 10) OIL_CHANGE ${DATE}T13:30:00Z > /tmp/kuss-par-$i.out & done; wait; head -1 /tmp/kuss-par-1.out /tmp/kuss-par-2.out
    curl -s "$BASE/api/v1/dealerships/$LONDON/appointments?date=$DATE" | jq --arg v "$(V 10)" '[.[]|select(.vehicleId==$v)]|length'
    ```
    Expect one 201 and the other either 201 (same `id`) or 409 `KUSS_IDEMPOTENCY_IN_FLIGHT`; day-list count for V10 is exactly `1`. Timing dependent: if both finish as replay it still passes.
12. **ID-12 24 h expiry (SQL ages the key; not Swagger)**
    ```sh
    bk case-id-12 $LONDON $(V 11) OIL_CHANGE ${DATE}T14:00:00Z; ID1=$(J .id)
    bk case-id-12 $LONDON $(V 11) OIL_CHANGE ${DATE}T14:00:00Z; echo "inside TTL id=$(J .id)"
    cx case-id-12-cancel $ID1
    $PSQL -c "update idempotency_key set created_at = now() - interval '23 hours' where idempotency_key='case-id-12'"
    bk case-id-12 $LONDON $(V 11) OIL_CHANGE ${DATE}T14:00:00Z; echo "23h id=$(J .id)"
    $PSQL -c "update idempotency_key set created_at = now() - interval '25 hours' where idempotency_key='case-id-12'"
    bk case-id-12 $LONDON $(V 11) OIL_CHANGE ${DATE}T14:00:00Z; echo "25h id=$(J .id)"
    ```
    Expect: first 201 `id=ID1`; replay 201 same id; cancel 200 `CANCELLED`; at 23 h the replay still returns `ID1` (stored body, so `status` shows `CONFIRMED`); at 25 h a fresh 201 with an `id` different from `ID1`.

## 5. Conflicts: capacity, qualified technician, whole duration

Uses: LONDON, NY, DATE, V01-V06 (London 15:00Z block), V13-V16 (ENGINE_DIAGNOSTIC), V17-V20 (New York). Keys `case-cf-NN`. Half-open intervals: back-to-back is allowed. Header block: same as section 4.

```sh
BASE=http://localhost:8080
LONDON=00000000-0000-0000-0001-000000000001
NY=00000000-0000-0000-0001-000000000002
VEHICLE=00000000-0000-0000-0005-000000000001
DATE=$(date -v+7d +%F 2>/dev/null || date -d '+7 days' +%F); echo "DATE=$DATE"
V() { printf '00000000-0000-0000-0005-%012d' "$1"; }
bk() { B=$(jq -nc --arg d "$2" --arg v "$3" --arg s "$4" --arg t "$5" '{dealershipId:$d,vehicleId:$v,serviceTypeCode:$s,desiredStartTime:$t}'); echo "BODY $B"
  curl -s -i -X POST $BASE/api/v1/appointments -H 'Content-Type: application/json' -H "Idempotency-Key: $1" -d "$B" | tr -d '\r' | sed -n '1p;/^[Xx]-[Rr]equest-[Ii]d/p;/^{/p' | tee /tmp/kuss-last; }
cx() { curl -s -i -X POST $BASE/api/v1/appointments/$2/cancel -H "Idempotency-Key: $1" | tr -d '\r' | sed -n '1p;/^[Xx]-[Rr]equest-[Ii]d/p;/^{/p' | tee /tmp/kuss-last; }
J() { sed -n '/^{/p' /tmp/kuss-last | jq -r "$1"; }
```

### 5.1 Bay-limited: London 15:00Z, 3 active bays, 4 OIL technicians

1. **CF-01 Three OIL bookings at 15:00Z**
   ```sh
   bk case-cf-01a $LONDON $(V 1) OIL_CHANGE ${DATE}T15:00:00Z; A1=$(J .id); J .serviceBayId
   bk case-cf-01b $LONDON $(V 2) OIL_CHANGE ${DATE}T15:00:00Z; J .serviceBayId
   bk case-cf-01c $LONDON $(V 3) OIL_CHANGE ${DATE}T15:00:00Z; J .serviceBayId
   ```
   Expect 201 x3 with three different `serviceBayId` (…101, …102, …103) and different technicians.
2. **CF-02 Fourth OIL at 15:00Z: techs free, no bay**
   ```sh
   bk case-cf-02 $LONDON $(V 4) OIL_CHANGE ${DATE}T15:00:00Z
   ```
   Expect 409, `KUSS_NO_CAPACITY`, `detail="No bay and technician are free for this slot."`.
3. **CF-03 60-minute TIRE at 14:30Z overlaps the 15:00-15:30 bookings on every bay**
   ```sh
   bk case-cf-03 $LONDON $(V 5) TIRE_SERVICE ${DATE}T14:30:00Z
   ```
   Expect 409, `KUSS_NO_CAPACITY`.
4. **CF-04 30-minute OIL at 14:30Z ends exactly at 15:00**
   ```sh
   bk case-cf-04 $LONDON $(V 5) OIL_CHANGE ${DATE}T14:30:00Z
   ```
   Expect 201, `endTime=DATET15:00:00Z`.
5. **CF-05 60-minute TIRE at 15:30Z, bays free again**
   ```sh
   bk case-cf-05 $LONDON $(V 6) TIRE_SERVICE ${DATE}T15:30:00Z
   ```
   Expect 201, `endTime=DATET16:30:00Z`.
6. **CF-06 Cancel frees the pair; same key as CF-02 now succeeds** (key `case-cf-02` reused on purpose)
   ```sh
   cx case-cf-06-cancel $A1
   bk case-cf-02 $LONDON $(V 4) OIL_CHANGE ${DATE}T15:00:00Z
   ```
   Expect cancel 200 `CANCELLED`; then 201 (a 409 stores no key, so no replay and no 422).

### 5.2 Technician-limited: ENGINE_DIAGNOSTIC, only London tech 105

1. **CF-07 120-minute diagnostic at 10:00Z**
   ```sh
   bk case-cf-07 $LONDON $(V 13) ENGINE_DIAGNOSTIC ${DATE}T10:00:00Z; J .technicianId
   ```
   Expect 201; `technicianId=00000000-0000-0000-0003-000000000105`; `endTime=DATET12:00:00Z`.
2. **CF-08 Diagnostic at 11:00Z overlaps the second hour**
   ```sh
   bk case-cf-08 $LONDON $(V 14) ENGINE_DIAGNOSTIC ${DATE}T11:00:00Z
   ```
   Expect 409, `KUSS_NO_CAPACITY` (bays are free, the only qualified tech is busy).
3. **CF-09 Diagnostic at 11:30Z**
   ```sh
   bk case-cf-09 $LONDON $(V 14) ENGINE_DIAGNOSTIC ${DATE}T11:30:00Z
   ```
   Expect 409, `KUSS_NO_CAPACITY`.
4. **CF-10 Diagnostic at 09:00Z (ends 11:00, overlaps the start)**
   ```sh
   bk case-cf-10 $LONDON $(V 14) ENGINE_DIAGNOSTIC ${DATE}T09:00:00Z
   ```
   Expect 409, `KUSS_NO_CAPACITY`.
5. **CF-11 Diagnostic at 12:00Z (back-to-back)**
   ```sh
   bk case-cf-11 $LONDON $(V 14) ENGINE_DIAGNOSTIC ${DATE}T12:00:00Z
   ```
   Expect 201; tech 105; `endTime=DATET14:00:00Z`.
6. **CF-12 OIL at 11:00Z while tech 105 is busy**
   ```sh
   bk case-cf-12 $LONDON $(V 15) OIL_CHANGE ${DATE}T11:00:00Z; J .technicianId
   ```
   Expect 201; `technicianId` is one of …101..104, never …105 (105 lacks OIL_CHANGE).
7. **CF-13 Diagnostic at 08:00Z (ends exactly 10:00)**
   ```sh
   bk case-cf-13 $LONDON $(V 16) ENGINE_DIAGNOSTIC ${DATE}T08:00:00Z
   ```
   Expect 201; tech 105.

### 5.3 30-minute vs 60-minute overlap: New York 10:00 EST, 2 bays, TIRE only tech 201

1. **CF-14 TIRE 60 min**
   ```sh
   bk case-cf-14 $NY $(V 17) TIRE_SERVICE ${DATE}T10:00:00-05:00; BAYX=$(J .serviceBayId); J .technicianId
   ```
   Expect 201; `technicianId=…0003-000000000201`; `startTime=DATET15:00:00Z`, `endTime=DATET16:00:00Z`.
2. **CF-15 OIL 30 min at the same time**
   ```sh
   bk case-cf-15 $NY $(V 18) OIL_CHANGE ${DATE}T10:00:00-05:00; BAYY=$(J .serviceBayId); echo "$BAYX vs $BAYY"; J .technicianId
   ```
   Expect 201; `technicianId=…0003-000000000202`; bay different from BAYX.
3. **CF-16 Another OIL at 10:00: both bays busy**
   ```sh
   bk case-cf-16 $NY $(V 19) OIL_CHANGE ${DATE}T10:00:00-05:00
   ```
   Expect 409, `KUSS_NO_CAPACITY`.
4. **CF-17 OIL at 10:30: the 30-minute booking's bay frees, the 60-minute one stays busy**
   ```sh
   bk case-cf-17 $NY $(V 19) OIL_CHANGE ${DATE}T10:30:00-05:00; echo "bay $(J .serviceBayId) expect $BAYY"
   ```
   Expect 201; `serviceBayId` equals BAYY; `technicianId=…202`.
5. **CF-18 TIRE at 10:30: sole TIRE tech busy until 11:00**
   ```sh
   bk case-cf-18 $NY $(V 20) TIRE_SERVICE ${DATE}T10:30:00-05:00
   ```
   Expect 409, `KUSS_NO_CAPACITY`.
6. **CF-19 TIRE at 11:00 (back-to-back)**
   ```sh
   bk case-cf-19 $NY $(V 20) TIRE_SERVICE ${DATE}T11:00:00-05:00
   ```
   Expect 201; `technicianId=…201`.

## 6. Vehicle conflicts (global per vehicle)

Uses: NY, LONDON, DATE, V21 (NY afternoon), V02 (booked by BK-02 at NY 08:00-05:00 = 13:00Z); keys `case-vh-NN`. Header block: same as section 5.

```sh
BASE=http://localhost:8080
LONDON=00000000-0000-0000-0001-000000000001
NY=00000000-0000-0000-0001-000000000002
VEHICLE=00000000-0000-0000-0005-000000000001
DATE=$(date -v+7d +%F 2>/dev/null || date -d '+7 days' +%F); echo "DATE=$DATE"
V() { printf '00000000-0000-0000-0005-%012d' "$1"; }
bk() { B=$(jq -nc --arg d "$2" --arg v "$3" --arg s "$4" --arg t "$5" '{dealershipId:$d,vehicleId:$v,serviceTypeCode:$s,desiredStartTime:$t}'); echo "BODY $B"
  curl -s -i -X POST $BASE/api/v1/appointments -H 'Content-Type: application/json' -H "Idempotency-Key: $1" -d "$B" | tr -d '\r' | sed -n '1p;/^[Xx]-[Rr]equest-[Ii]d/p;/^{/p' | tee /tmp/kuss-last; }
```

1. **VH-01 V21 OIL at NY 12:00**
   ```sh
   bk case-vh-01 $NY $(V 21) OIL_CHANGE ${DATE}T12:00:00-05:00
   ```
   Expect 201.
2. **VH-02 V21 TIRE at 12:30 (back-to-back with VH-01)**
   ```sh
   bk case-vh-02 $NY $(V 21) TIRE_SERVICE ${DATE}T12:30:00-05:00
   ```
   Expect 201; `endTime=DATET18:30:00Z`.
3. **VH-03 V21 TIRE at 12:00, new key**
   ```sh
   bk case-vh-03 $NY $(V 21) TIRE_SERVICE ${DATE}T12:00:00-05:00
   ```
   Expect 409, `KUSS_VEHICLE_ALREADY_BOOKED` (not NO_CAPACITY).
4. **VH-04 V21 OIL at 13:00, overlapping the tail of VH-02**
   ```sh
   bk case-vh-04 $NY $(V 21) OIL_CHANGE ${DATE}T13:00:00-05:00
   ```
   Expect 409, `KUSS_VEHICLE_ALREADY_BOOKED`.
5. **VH-05 V21 OIL at 13:30**
   ```sh
   bk case-vh-05 $NY $(V 21) OIL_CHANGE ${DATE}T13:30:00-05:00
   ```
   Expect 201.
6. **VH-06 Same vehicle at another dealership in the same instant** (V02 holds NY 13:00Z-13:30Z from BK-02)
   ```sh
   bk case-vh-06 $LONDON $(V 2) OIL_CHANGE ${DATE}T13:00:00Z
   ```
   Expect 409, `KUSS_VEHICLE_ALREADY_BOOKED`.

## 7. Cancel and cancel-then-rebook

Uses: NY, DATE, V22 (14:30 EST), V23 (14:30 EST rebook), V24 (15:30 EST); keys `case-cn-NN`, cancel keys `case-cn-NN-cancel`. Header block: same as section 4 (with `PSQL`, `bk`, `cx`, `J`).

```sh
BASE=http://localhost:8080
LONDON=00000000-0000-0000-0001-000000000001
NY=00000000-0000-0000-0001-000000000002
VEHICLE=00000000-0000-0000-0005-000000000001
DATE=$(date -v+7d +%F 2>/dev/null || date -d '+7 days' +%F); echo "DATE=$DATE"
PSQL="podman exec kuss04-bugfix-db-1 psql -U kuss -d kuss"
V() { printf '00000000-0000-0000-0005-%012d' "$1"; }
bk() { B=$(jq -nc --arg d "$2" --arg v "$3" --arg s "$4" --arg t "$5" '{dealershipId:$d,vehicleId:$v,serviceTypeCode:$s,desiredStartTime:$t}'); echo "BODY $B"
  curl -s -i -X POST $BASE/api/v1/appointments -H 'Content-Type: application/json' -H "Idempotency-Key: $1" -d "$B" | tr -d '\r' | sed -n '1p;/^[Xx]-[Rr]equest-[Ii]d/p;/^{/p' | tee /tmp/kuss-last; }
cx() { curl -s -i -X POST $BASE/api/v1/appointments/$2/cancel -H "Idempotency-Key: $1" | tr -d '\r' | sed -n '1p;/^[Xx]-[Rr]equest-[Ii]d/p;/^{/p' | tee /tmp/kuss-last; }
J() { sed -n '/^{/p' /tmp/kuss-last | jq -r "$1"; }
```

1. **CN-01 Cancel a confirmed future appointment** (Swagger: POST /api/v1/appointments/{appointmentId}/cancel, header `Idempotency-Key: case-cn-01-cancel`)
   ```sh
   bk case-cn-01 $NY $(V 22) OIL_CHANGE ${DATE}T14:30:00-05:00; ID22=$(J .id)
   cx case-cn-01-cancel $ID22
   ```
   Expect booking 201; cancel 200 with `status=CANCELLED`, `cancelledAt` non-null, other fields unchanged.
2. **CN-02 GET after cancel**
   ```sh
   curl -s $BASE/api/v1/appointments/$ID22 | jq '{id,status,cancelledAt}'
   ```
   Expect 200, `status=CANCELLED`.
3. **CN-03 Day list still lists it**
   ```sh
   curl -s "$BASE/api/v1/dealerships/$NY/appointments?date=$DATE" | jq --arg id "$ID22" '.[]|select(.id==$id)|{id,status}'
   ```
   Expect 200; entry with `status=CANCELLED`.
4. **CN-04 Cancel replay, same key**
   ```sh
   cx case-cn-01-cancel $ID22
   ```
   Expect 200, identical body (same `cancelledAt`).
5. **CN-05 Cancel again with a new key**
   ```sh
   cx case-cn-05-cancel $ID22
   ```
   Expect 200, same `cancelledAt` as CN-01.
6. **CN-06 Cancel unknown appointment**
   ```sh
   cx case-cn-06-cancel 00000000-0000-0000-0009-000000000001
   ```
   Expect 404, `KUSS_RESOURCE_NOT_FOUND`, `detail="Appointment not found."`.
7. **CN-07 Cancel without key**
   ```sh
   curl -s -i -X POST $BASE/api/v1/appointments/$ID22/cancel
   ```
   Expect 400, `KUSS_IDEMPOTENCY_KEY_MISSING`.
8. **CN-08 Cancel with a non-UUID id**
   ```sh
   cx case-cn-08-cancel not-a-uuid
   ```
   Expect 400, `KUSS_VALIDATION_FAILED`.
9. **CN-09 Cancel an appointment that already started (SQL moves its start into the past; not Swagger)**
   ```sh
   bk case-cn-09 $NY $(V 24) OIL_CHANGE ${DATE}T15:30:00-05:00; ID24=$(J .id)
   $PSQL -c "update appointment set start_time=now()-interval '10 minutes', end_time=now()+interval '20 minutes' where id='$ID24'"
   cx case-cn-09-cancel $ID24
   curl -s $BASE/api/v1/appointments/$ID24 | jq '{status}'
   ```
   Expect cancel 409, `KUSS_APPOINTMENT_ALREADY_STARTED`, `detail="Appointment has already started."`; GET `{"status":"CONFIRMED"}`.
10. **CN-10 Cancel then rebook the freed slot, both bays used**
    ```sh
    bk case-cn-10a $NY $(V 23) OIL_CHANGE ${DATE}T14:30:00-05:00; ID23=$(J .id)
    bk case-cn-10b $NY $(V 22) OIL_CHANGE ${DATE}T14:30:00-05:00
    curl -s "$BASE/api/v1/dealerships/$NY/availability?date=$DATE&serviceType=OIL_CHANGE" | jq -c '[.slots[]|select(.startTime|test("T14:30:00-05:00"))]'
    ```
    Expect 201 for V23 (slot freed by CN-01), 201 for V22 (vehicle freed by the cancel), and the availability filter returns `[]` (2 bays used, capacity 0, slot omitted).
11. **CN-11 Cancel key reused for a different appointment**
    ```sh
    cx case-cn-01-cancel $ID23
    ```
    Expect 422, `KUSS_IDEMPOTENCY_KEY_MISMATCH`; V23's appointment stays CONFIRMED.

## 8. Reads

Uses: LONDON, NY, DATE; bookings from sections 3-7 (RD-01/04/06 need them). Keys: none. Header block: BASE, LONDON, NY, VEHICLE, DATE, `T` helper (as section 2).

```sh
BASE=http://localhost:8080
LONDON=00000000-0000-0000-0001-000000000001
NY=00000000-0000-0000-0001-000000000002
VEHICLE=00000000-0000-0000-0005-000000000001
DATE=$(date -v+7d +%F 2>/dev/null || date -d '+7 days' +%F); echo "DATE=$DATE"
T() { date -v"$1"d +%F 2>/dev/null || date -d "$1 days" +%F; }
```

1. **RD-01 GET appointment by id** (Swagger: GET /api/v1/appointments/{appointmentId}; use `ID_BK01` printed by BK-01)
   ```sh
   curl -s $BASE/api/v1/appointments/$ID_BK01 | jq
   ```
   Expect 200; same fields as the BK-01 201 body. Not run.
2. **RD-02 Unknown id**
   ```sh
   curl -s -i $BASE/api/v1/appointments/00000000-0000-0000-0009-000000000001
   ```
   Expect 404, `KUSS_RESOURCE_NOT_FOUND`. Live: RAN, pass.
3. **RD-03 Non-UUID id**
   ```sh
   curl -s -i $BASE/api/v1/appointments/xyz
   ```
   Expect 400, `KUSS_VALIDATION_FAILED`. Live: RAN, pass.
4. **RD-04 London day list is ordered**
   ```sh
   curl -s "$BASE/api/v1/dealerships/$LONDON/appointments?date=$DATE" | jq '[.[].startTime] as $s | {n:($s|length), sorted:($s==($s|sort))}'
   ```
   Expect 200; `sorted=true`; `n` >= 1 (appointments from sections 3-5). Not run.
5. **RD-05 [today-relative] day-list bounds**
   ```sh
   for o in -365 -366 +90 +91; do curl -s "$BASE/api/v1/dealerships/$LONDON/appointments?date=$(T $o)" | jq -c "{o:\"$o\",r:(if type==\"array\" then length else {status,detail} end)}"; done
   ```
   Expect `-365` -> `0`; `-366` -> 400 `Date is more than 365 days back.`; `+90` -> `0` when no earlier case booked London on that day, or `1` after BK-10 (which books London on today+90 in the same run); `+91` -> 400 `Date is more than 90 days ahead.` Live: RAN, pass.
6. **RD-06 New York local day list**
   ```sh
   curl -s "$BASE/api/v1/dealerships/$NY/appointments?date=$DATE" | jq -c '.[]|{startTime,status}'
   ```
   Expect 200; contains `DATET13:00:00Z` (BK-02) and the CN/VH/CF New York bookings. Not run.
7. **RD-07 Unknown dealership**
   ```sh
   curl -s "$BASE/api/v1/dealerships/00000000-0000-0000-0001-000000000099/appointments?date=$DATE" | jq '{status,code}'
   ```
   Expect 404, `KUSS_RESOURCE_NOT_FOUND`. Not run.
8. **RD-08 Missing `date`**
   ```sh
   curl -s "$BASE/api/v1/dealerships/$LONDON/appointments" | jq '{status,code}'
   ```
   Expect 400, `KUSS_VALIDATION_FAILED`. Live: RAN, pass.

## 9. Errors and headers

Uses: BASE, DATE; any earlier error. Header block as section 8.

```sh
BASE=http://localhost:8080
LONDON=00000000-0000-0000-0001-000000000001
NY=00000000-0000-0000-0001-000000000002
VEHICLE=00000000-0000-0000-0005-000000000001
DATE=$(date -v+7d +%F 2>/dev/null || date -d '+7 days' +%F); echo "DATE=$DATE"
PSQL="podman exec kuss04-bugfix-db-1 psql -U kuss -d kuss"
```

1. **EH-01 `X-Request-Id` on success**
   ```sh
   curl -s -D- -o /dev/null $BASE/api/v1/service-types | grep -i x-request-id
   ```
   Expect a UUID header. Live: RAN, pass.
2. **EH-02 Error `requestId` equals header**
   ```sh
   curl -s -D /tmp/kuss-h $BASE/api/v1/appointments/00000000-0000-0000-0009-000000000001 | jq -r .requestId; grep -i x-request-id /tmp/kuss-h
   ```
   Expect the same UUID twice. Live: RAN, pass.
3. **EH-03 Id is server generated; a client value is ignored**
   ```sh
   curl -s -D- -o /dev/null -H 'X-Request-Id: my-own-id' $BASE/api/v1/service-types | grep -i x-request-id
   ```
   Expect a fresh UUID, not `my-own-id`; two calls give two different values. Live: RAN, pass.
4. **EH-04 Error body shape**
   ```sh
   curl -s -i $BASE/api/v1/appointments/00000000-0000-0000-0009-000000000001
   ```
   Expect `Content-Type: application/problem+json`; keys exactly `code, detail, instance, requestId, status, title` (no `type`, see M1). Live: RAN, pass.
5. **EH-05 405 keeps its status**
   ```sh
   curl -s -i -X DELETE $BASE/api/v1/dealerships | head -1
   curl -s -i $BASE/api/v1/appointments/00000000-0000-0000-0009-000000000001/cancel | head -1
   ```
   Expect 405 twice; body `code=KUSS_VALIDATION_FAILED`, `detail="Method not allowed."`. Live: RAN, pass.
6. **EH-06 415 on wrong content type**: see BK-19. Live: RAN, 415.
7. **EH-07 Unknown path**
   ```sh
   curl -s -i $BASE/api/v1/nope
   ```
   Expect 404, `KUSS_RESOURCE_NOT_FOUND`, `detail="Resource not found."`. Live: RAN, pass.
8. **EH-08 No stack trace or SQL in any `detail`**
   ```sh
   curl -s $BASE/api/v1/appointments/xyz | jq -r .detail | grep -Ei 'exception|at com\.|select |constraint' || echo CLEAN
   ```
   Expect `CLEAN` (repeat on any 409/400 response from earlier sections). Not run beyond this GET sample.
9. **EH-09 Tomcat-level rejection**
   ```sh
   curl -s -i "$BASE/api/v1/dealerships/%zz"
   ```
   Expect 400, `code=KUSS_VALIDATION_FAILED`, `detail="Invalid request."`, no `requestId`, no `instance`, no `X-Request-Id` header. Live: RAN, pass.
10. **EH-10 Audit rows (SQL; the write is async, retry after a few seconds)**
    ```sh
    $PSQL -c "select request_id,http_method,path,http_status,error_code from request_audit order by occurred_at desc limit 10"
    ```
    Expect a row per request of the suite with `http_status` and, for failures, `error_code` equal to the KUSS code. Not run.
11. **EH-11 `Retry-After: 1` on `503 KUSS_SERVICE_BUSY`**: NOTE only. Needs pool exhaustion or a statement timeout; not triggerable manually (integration tests cover it).
12. **EH-12 `500 KUSS_INTERNAL_ERROR`**: NOTE only; not triggerable manually.

## 10. Documentation and health

Uses: BASE only. Header block: `BASE=http://localhost:8080` (plus `PSQL` for DH-08).

```sh
BASE=http://localhost:8080
PSQL="podman exec kuss04-bugfix-db-1 psql -U kuss -d kuss"
LONDON=00000000-0000-0000-0001-000000000001
VEHICLE=00000000-0000-0000-0005-000000000023
DATE=$(date -v+7d +%F 2>/dev/null || date -d '+7 days' +%F); echo "DATE=$DATE"
```

1. **DH-01 Swagger UI**
   ```sh
   curl -s -o /dev/null -w '%{http_code} %{redirect_url}\n' $BASE/swagger-ui.html; curl -s -o /dev/null -w '%{http_code}\n' $BASE/swagger-ui/index.html
   ```
   Expect `302 http://localhost:8080/swagger-ui/index.html`, then `200`. Live: RAN, pass.
2. **DH-02 OpenAPI document**
   ```sh
   curl -s $BASE/v3/api-docs | jq -c '{v:.openapi,paths:(.paths|keys),post:(.paths["/api/v1/appointments"].post.parameters|map({name,in,required}))}'
   ```
   Expect `openapi 3.1.0`; paths: appointments, appointments/{appointmentId}, …/cancel, customers, dealerships, dealerships/{dealershipId}, …/appointments, …/availability, service-types, plus `/error`; header `Idempotency-Key` required. Live: RAN, pass.
3. **DH-03 Health**
   ```sh
   curl -s $BASE/actuator/health
   ```
   Expect 200 `{"groups":["liveness","readiness"],"status":"UP"}`. Live: RAN, pass.
4. **DH-04 Liveness and readiness**
   ```sh
   curl -s -o /dev/null -w '%{http_code}\n' $BASE/actuator/health/liveness $BASE/actuator/health/readiness
   ```
   Expect `200` twice. Live: RAN, pass.
5. **DH-05 Info**
   ```sh
   curl -s -o /dev/null -w '%{http_code}\n' $BASE/actuator/info
   ```
   Expect `200`. Live: RAN, pass.
6. **DH-06 Prometheus metrics**
   ```sh
   curl -s $BASE/actuator/prometheus | grep -o '^scheduler[a-z_]*' | sort -u
   ```
   Expect `scheduler_audit_writes_total`, `scheduler_availability_query_duration_seconds_*`, `scheduler_booking_gist_retries_total`, `scheduler_booking_requests_total`. Live: RAN, pass.
7. **DH-07 Other actuator endpoints are not exposed**
   ```sh
   curl -s -o /dev/null -w '%{http_code}\n' $BASE/actuator/metrics
   ```
   Expect `404`. Live: RAN, pass.
8. **DH-08 Actuator, docs and Swagger requests are not audited (SQL)**
   ```sh
   $PSQL -c "select count(*) from request_audit where path like '/actuator%' or path like '/swagger%' or path like '/v3%'"
   ```
   Expect `0`. Not run.
9. **DH-09 Swagger end-to-end** (Swagger UI only; key `case-dh-09`)
   1. GET availability: LONDON, date=DATE, serviceType=OIL_CHANGE; note a free slot, e.g. `DATET11:30:00Z` with `availableCapacity>=1`.
   2. POST /api/v1/appointments, header `Idempotency-Key: case-dh-09`, body `{"dealershipId":"00000000-0000-0000-0001-000000000001","vehicleId":"00000000-0000-0000-0005-000000000023","serviceTypeCode":"OIL_CHANGE","desiredStartTime":"DATET11:30:00Z"}` (type DATE). Expect 201, `status=CONFIRMED`; copy `id`.
   3. GET /api/v1/appointments/{id}: expect 200 with the same fields.
   4. POST /api/v1/appointments/{id}/cancel, header `Idempotency-Key: case-dh-09-cancel`: expect 200, `status=CANCELLED`.
   5. GET availability again: the 11:30 capacity is back to its value from step 1.
   Not run.

---

## 11. Mismatches between brief, docs and code

- **M1 (docs aligned to code; code unchanged):** fixed in README.md ("Errors are RFC 9457" bullet) and docs/system-design.md §4.8. App-level errors, `/error` included, have `code, detail, instance, requestId, status, title` and no `type` (SchedulerGlobalHandler.java:180-193); the Tomcat-level body has `type` `about:blank` and no `instance` or `requestId` (ProblemDetailErrorReportValve.java:23-26).
- **M3 (docs aligned to code; code unchanged):** fixed in README.md (Swagger/OpenAPI bullet) and docs/system-design.md §4.7. `/v3/api-docs` declares only `200` for every operation, booking included (no `@ApiResponse`, AppointmentController.java:33-54); the docs now say the OpenAPI document holds success responses only and the errors live in the README error table and §4.8. `/error` is listed as an OpenAPI path.
- **M4 (docs aligned to code; code unchanged):** fixed in README.md (`X-Request-Id` bullet). The server always assigns the id (RequestAuditFilter.java:61-64); a client-sent `X-Request-Id` is ignored and the response carries the server's id.
- **M5 (docs aligned to code; code unchanged):** fixed in docs/system-design.md §4.2 ("Check order in one attempt"). Order: key claim, 404s (dealership, vehicle, service type), horizon then slot 400s, vehicle 409, capacity 409 (BookingAttemptHelper.java:56-60, 73-82, 100-102, 130-142). A request that is unknown-vehicle and off-grid returns 404.
- **M6 (docs aligned to code; code unchanged):** fixed in docs/system-design.md §4.2 (Replay and mismatch). The hash check runs before the in-flight check (IdempotencyServiceImpl.java:100-106), so IN_FLIGHT from a stored row needs an identical body; ID-10 builds the hash for that reason. The `23505` race (IdempotencyServiceImpl.java:91-95) gives IN_FLIGHT without a hash check.
- **BK-17 (resolved):** a `desiredStartTime` without an offset returns 400 `Malformed request.` (verified live, 2026-11-01). It does not return 201.
- Docs and code agree on: date bounds (src/main/java/com/keyloop/scheduler/common/util/TimeSlotUtils.java:21-22, 73, 93), 24 h TTL (IdempotencyServiceImpl.java:72-73), `isActive` on bays and technicians (ServiceBayDto.java:7, TechnicianDto.java:8), `Retry-After: 1` (SchedulerGlobalHandler.java:40, 48), slots with zero capacity omitted (AvailabilitySweepUtils.java:38).
- Brief ambiguity (assumptions, not defects): the brief gives no grid, opening-hours or lead-time rule; the design assumes a 30-minute grid anchored at opening, a 90-day horizon, and a customer derived from the vehicle (the request carries no customer id).
