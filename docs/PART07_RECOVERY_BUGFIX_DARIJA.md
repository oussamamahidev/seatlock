# Part 7: tashih dyal automatic booking recovery

Validation t-daret nhar 16 novembre 2026, timezone Africa/Casablanca.

## 1. Sabab lmochkil li khalla job waqef

F `booking-service/src/main/java/com/seatlock/booking/BookingApplication.java`,
`@EnableScheduling` ma kantch. `BookingRecoveryWorker` kan Spring bean, w `poll()`
3andha `@Scheduled`, walakin Spring ma kanch kayregister ltask bach y3ayet liha.
`@Scheduled` bo7dha ma katkafich bla scheduling configuration.

Lprocess li kan khddam 3ando `RECOVERY_ENABLED=true`. Booking w Inventory kano
`UP`, migrations V1/V2 appliques, w schema dyal jobs s7i7. Lbooking
`b264193c-c8d3-4ca5-9983-87ca307dd6f7` kan `UNCERTAIN`, job `READY`, due,
`attempt_count=0`, bla lease. Hadchi tchecka b queries read-only 9bel restart.

Fin kan kayw9ef lifecycle: **9bel `RecoveryJobStore.claim()`**, 7it scheduler ma
kanch kay3ayet l `BookingRecoveryWorker.poll()` aslan.

Tests l9dam kano kay3ayto l `poll()` direct, w integration tests dyal queue kano
dayrin `seatlock.recovery.enabled=false`. Dakchi 3lach 17 tests kano pass w
automatic recovery ma khddamch.

Ma l9inach mochkil dyal native SQL mapping Hibernate f claim: `claim()` kayst3mel
`JdbcTemplate`, UUID mapping direct, w transaction Spring. Tests PostgreSQL dyal
claim w concurrent claims kano pass 7tta 9bel fix. Ma bddlna la schema la Flyway.

Metrics li ma banouch ma kanoch dalil bo7dhom: l9dam counters/timer kano
kaytregisteraw mlli job kaytprocessa. Worker li ma 3emro t3ayet ma kaybanch f had
metrics.

## 2. Bug tani: lease katssali w transaction katsenna row lock

Test zedna l9a race 7a9i9i f `RecoveryJobStore.ownsLease()`, `finish()` w
`postpone()`.

F PostgreSQL, `now()` katb9a waqt bidayat transaction. Ila worker bda transaction,
tssna booking row lock, w lease salat f dak lwa9t, `lease_until > now()` t9der
tb9a true 7it `now()` ma t7errekatch. Ltest 9bel fix rje3 `CONFIRMED` b lease
salat, w lmatloub howa tb9a booking `PENDING`.

Bddlna **ghir comparisons dyal lease validity** l `clock_timestamp()`, li
kat9ra lwa9t l7a9i9i f PostgreSQL. Token fencing, claim SQL, transactions w
booking locking b9aw kif kano. Claim ba9i kayst3mel `FOR UPDATE OF j SKIP LOCKED`.

## 3. Chno tbddel f code

| Fichier | Chno tbdal |
| --- | --- |
| `BookingApplication.java` | Zedna `@EnableScheduling`. |
| `BookingRecoveryWorker.java` | Counters/timer kaytregisteraw mn startup; polls, claimed jobs, claim errors w processing errors; logs b booking ID, attempt w status; exceptions b stack trace; failure dyal job wa7ed ma kay7bessch batch kamel. |
| `RecoveryJobStore.java` | `clock_timestamp()` f guards dyal `ownsLease`, `finish`, `postpone`. |
| `BookingRecoverySchedulerIntegrationTest.java` | Scheduler Spring 7a9i9i + PostgreSQL Testcontainers; ma kay3ayetch l `poll()` direct; InventoryPort mocked bach n3ezlo test dyal scheduling. Tasks katsed 9bel ma PostgreSQL container ytsed. |
| `BookingRecoveryJobIntegrationTest.java` | Due/non-due, concurrent claims, locked rows, expired leases, stale fencing, lock-wait expiry, backoff, exhaustion w terminal consistency. |
| `BookingRecoveryWorkerTest.java` | Idle metrics, claim failure/retry, processing failure isolation; tests dyal same booking ID w transient failure ba9yin. |

REST API, security, database migrations w architecture ma tbddlouch.

## 4. Lifecycle dyal transactions

```text
Transaction A: INSERT booking + INSERT recovery job -> COMMIT
                           |
          synchronous Inventory attempt, bla DB lock
                           |
    unknown outcome -> booking UNCERTAIN / job READY
                           |
             Spring scheduler -> poll()
                           |
Transaction B: claim due rows / SKIP LOCKED
               token jdid + lease + attempt_count++ -> COMMIT
                           |
            Inventory retry b nafs booking UUID
                  bla transaction DB maftou7a
                           |
Transaction C: lock booking + verify token AND wall-clock lease
               |
               +-- terminal result -> booking CONFIRMED/REJECTED + job DONE
               +-- unknown -> booking UNCERTAIN + backoff + job READY
               +-- max attempts -> booking UNCERTAIN + job EXHAUSTED
               +-- lease lost -> ma ybeddelch booking/job
                           |
                         COMMIT
```

ACID: insert dyal booking w job f nafs local transaction. Settlement dyal
booking w job f nafs transaction zada. Ila fenced update f `finish/postpone`
ma bddl 7tta row, exception katdir rollback l transaction kamla, 7tta booking.

Concurrency: `SKIP LOCKED` kaykhelli workers yakhdou jobs mokhtalfin. Claim
update wa7ed atomique. New owner kayakhod token jdid; old worker ma y9derch
yacknowledgi b token 9dim. Wall-clock guard kaymna3 7tta lease salat bla new owner.

Idempotency: retries kaymchiw b **nafs booking ID**, machi UUID jdid. Ila worker
mat mor Inventory commit, retry kayl9a nafs reservation; ma kay7jezch seat tani.

Separation dyal responsibilities b9at: scheduler kayorchestrate, `RecoveryJobStore`
kaysiyer queue/leases, `BookingStore` kaysiyer local transactions, `InventoryPort`
kay3zel remote call, w `RetryBackoff` kay7seb delay.

## 5. Tests li t-executaw

Kol integration data dyal tests kayn f PostgreSQL containers jetables.
Synthetic lease/due timestamps tbddlou ghir tma. Local booking databases ma
tdar fihom la delete, la truncate, la manual terminal-state update.

Baseline:

```bash
mvn -o -B -f booking-service/pom.xml test
```

Result: **17 tests pass**. Hadchi byyen bli tests l9dam ma kaycoveriwch scheduling.

Regression scheduling 9bel `@EnableScheduling`:

```bash
mvn -o -B -f booking-service/pom.xml -Dtest=BookingRecoverySchedulerIntegrationTest test
```

Result: **FAIL**, job b9a `UNCERTAIN`, expected `CONFIRMED`, timeout 6 seconds.

Regression lease 9bel `clock_timestamp()`:

```bash
mvn -o -B -f booking-service/pom.xml '-Dtest=BookingRecoveryJobIntegrationTest#leaseExpiringWhileWaitingForBookingLockCannotCommitAStaleResult' test
```

Result: **FAIL**, expected `PENDING`, actual `CONFIRMED` b lease expired.

Final command mor joj fixes:

```bash
mvn -o -B -f booking-service/pom.xml clean test
```

Result: **28 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS**.

| Groupe | Tests |
| --- | ---: |
| Real Spring scheduler + due/future jobs + metrics | 1 |
| PostgreSQL recovery queue, locks, fencing, retry lifecycle | 9 |
| Booking persistence | 2 |
| Booking workflow | 6 |
| Recovery worker unit tests | 5 |
| Retry backoff | 2 |
| Realm role converter | 3 |

## 6. Validation live li dazet

Booking Service trestarta b build jdida w environment dyal process l9dim,
bla ma passwords/secrets/tokens ytb3ou. Inventory t7bess b SIGTERM f demo
w trestarta mor create. Gateway, Keycloak w PostgreSQL b9aw khddamin.

| Booking ID | 9bel | Mor automatic recovery |
| --- | --- | --- |
| `b264193c-c8d3-4ca5-9983-87ca307dd6f7` | UNCERTAIN / READY / attempt 0 | **CONFIRMED / DONE / attempt 1** |
| `a5a533a4-f8e5-4e2b-98e6-6f73f1174f76` (demo jdida) | HTTP 202 UNCERTAIN / READY / attempt 0 | **CONFIRMED / DONE / attempt 1** |

F joj bookings, `seats.reservation_id` f Inventory howa exactement nafs booking
UUID. Owner token w lease t7ydo automatiquement mlli job wlat DONE.

Worker traite zada job due 9dima `9910cf0f-c12c-459f-bc27-295f2f50704d`:
**REJECTED / DONE / attempt 1**, reason `Seat is unavailable`, mn normal Inventory
response. Ma tbdlat 7tta state b SQL manuellement.

Ldemo jdida dazet b had tartib:

1. Inventory UP, seat `fdc4ee56-6308-4934-be3b-283762f547c3` AVAILABLE.
2. Inventory t7bess.
3. POST booking via Gateway -> HTTP 202 / UNCERTAIN.
4. Read-only SQL check -> READY job, attempt 0.
5. Inventory trestarta w health rje3 UP.
6. Scheduler claim f thread `scheduling-1`.
7. Inventory reserved seat b nafs booking ID.
8. Booking CONFIRMED, job DONE; authenticated Gateway GET -> HTTP 200.

**Zero manual `/retry` calls. Zero direct DB writes mn validation script.**

Prometheus snapshot mor had lifecycle:

```text
seatlock_recovery_claimed_total 3.0
seatlock_recovery_claim_errors_total 0.0
seatlock_recovery_errors_total 0.0
seatlock_recovery_outcomes_total{status="CONFIRMED"} 2.0
seatlock_recovery_outcomes_total{status="REJECTED"} 1.0
seatlock_recovery_duration_seconds_count 3
seatlock_recovery_polls_total 4.0
```

Polls counter ghadi yb9a yzid kol 2 seconds 7tta bla jobs.

## 7. Ubuntu: kifach t3awed tverify

Build/tests (Docker khaso ykoun khddam; `-o` kayst3mel dependencies f cache):

```bash
cd ~/Desktop/seatlock
mvn -o -B -f booking-service/pom.xml clean test
```

Services daba khddamin. Ma tlaunchich instance taniya f nafs port.
Ila bghiti restart mn terminal dyalek, wa9ef ghir process Booking li verifyiti
identity dyalo, b3d men `killall java`:

```bash
lsof -nP -iTCP:8083 -sTCP:LISTEN
```

Mor stop dyal linstance l9dima, f terminal Booking:

```bash
cd ~/Desktop/seatlock/booking-service
read -r -s -p 'Booking DB password: ' BOOKING_DB_PASSWORD
echo
export BOOKING_DB_PASSWORD
read -r -s -p 'Booking machine secret: ' BOOKING_MACHINE_SECRET
echo
export BOOKING_MACHINE_SECRET
export RECOVERY_ENABLED=true
mvn spring-boot:run
```

Read-only verification dyal original booking, `psql` ghadi yetleb password b prompt:

```bash
psql -h 127.0.0.1 -p 5433 -U booking_user -d booking_db -W
```

```sql
SELECT b.id, b.status, j.state, j.attempt_count,
       j.owner_token, j.lease_until, j.last_error
FROM booking_recovery_jobs j
JOIN bookings b ON b.id = j.booking_id
WHERE b.id = 'b264193c-c8d3-4ca5-9983-87ca307dd6f7';
```

Metrics w logs:

```bash
curl -fsS http://localhost:8083/actuator/health
curl -fsS http://localhost:8083/actuator/prometheus | rg '^seatlock_recovery'
tail -f /tmp/seatlock-part07-booking-service.log
```

Outage demo: f root, run `python3 scripts/part07_recovery_demo.py pick` mlli
Inventory UP. Save **real seat UUID** li script kaytbe3. Wa9ef Inventory bo7do,
w run:

```bash
read -r -p 'Seat UUID li 3tak pick: ' SEAT_ID
python3 scripts/part07_recovery_demo.py create "$SEAT_ID"
```

Rje3 Inventory UP. Copy exact `watch` command li `create` kaytbe3. Ma tktebch
`SEAT_UUID`/`BOOKING_UUID` litteralement: hadouk placeholders.

## 8. Limits w evidence

- Juj concurrent transactions ttestaw f PostgreSQL; ma launchinach juj Booking
  JVMs f live f nafs lwa9t. `SKIP LOCKED` w fencing ttestaw b real row locks.
- Process crash/lease expiry t-simulaw f disposable DB. Live demo testat
  Inventory outage/restart, machi crash dyal Booking f nos settlement.
- Scheduler integration test kaymocki InventoryPort; live scenario testat actual
  HTTP, Gateway, Keycloak machine credentials, JWT roles/audience w reservation.
- Backoff/exhaustion ttestaw b synthetic timestamps; ma tsenninach 12 live retries.
- Logs w snapshot f `/tmp/seatlock-part07-fixed-tests.log`,
  `/tmp/seatlock-part07-regression-before.log`, `/tmp/seatlock-part07-fencing-before.log`,
  `/tmp/seatlock-part07-live-validation.log`, `/tmp/seatlock-part07-live-evidence.json`.
  `/tmp` temporary, ma t3tamedch 3lih b7al permanent report.
- Inventory w Booking b9aw UP mor validation. Existing bookings ma t7ydouch;
  eligible jobs tprocessaw ghir mn worker normal.

Sources officielles:

- [Spring: EnableScheduling](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/scheduling/annotation/EnableScheduling.html)
- [PostgreSQL: date/time functions, now() w clock_timestamp()](https://www.postgresql.org/docs/current/functions-datetime.html)
- [PostgreSQL: SELECT w SKIP LOCKED](https://www.postgresql.org/docs/current/sql-select.html)
