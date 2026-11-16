# SeatLock — Part 7.5: Backend Production Hardening (Darija / clavier FR)

**Statut:** source patch prepared; Java syntax / YAML / Python checks done. `mvn verify`, PostgreSQL/Testcontainers, Keycloak, Docker, real HTTP and k6 **must run on your Ubuntu**. This is NOT a claim of production readiness.

## 0. Chno zedna? (b7al vrai backend engineer)

Part 7 kan fiha Booking `PENDING → CONFIRMED | REJECTED | UNCERTAIN`, recovery job b PostgreSQL, OAuth2 machine identity, Gateway, Redis. Daba Part 7.5 katzid **business lifecycle + proof under concurrency**:

- **Temporary seat hold**: `AVAILABLE → HELD`, expiry after 120s (configurable 10–600), cancel before booking. ACTIVE hold kayblocki bookings dyal ness okhrin.
- **Confirm a held seat**: user kaycreate Booking m3a `holdId`, Booking kay3ayet Inventory b machine token w original customerId; only owner y9der yconfirmi hold li mazal valid.
- **Permanent confirmed booking**: `HELD → RESERVED`; `RESERVED` ma 3ndhach TTL. Ila auto-expire CONFIRMED, ghadi ndiro loss of data/business consistency.
- **Cancel confirmed booking (durable Saga)**: `CONFIRMED → CANCEL_PENDING → CANCELLED`. Worker kayrelease Inventory b original bookingId, kayretry ila service down, w kaymarki booking CANCELLED in Booking DB. Job kayt7fed f nafs ACID transaction m3a `CANCEL_PENDING`.
- **Reservation tombstone**: f Inventory `seat_reservation_history(bookingId, seatId, state ACTIVE/CANCELLED)`. Ila response qdima / delayed retry jat mlli booking CANCELLED, history kayrefusi re-reserve. Hadchi critical.
- **Concurrency / load test**: Testcontainers, HTTP burst, k6 script. Exactly one winner for same seat.
- **Multi-instance**: 2 Booking JVMs (8083/8084) m3a Nginx (8085), Gateway (8080). Same PostgreSQL w same job queues b `FOR UPDATE SKIP LOCKED`.

### Architecture

```text
Browser/Curl/React (customer access token)
                 |
         Gateway :8080 (JWT + routing + optional Redis rate limit)
                 |
      +----------+----------+
      |                     |
Booking A :8083        Booking B :8084     (Nginx 8085 in optional lab)
      +----------+----------+
                 |
             booking_db
         bookings, recovery_jobs,
         cancellation_jobs
                 |
         client_credentials JWT
                 |
           Inventory :8082
                 |
             inventory_db
       seats, hold_requests,
       reservation_history
```

## 1. Sécurité w ACID — 3lach l design mzyan?

**Atomicity:** Inventory `UPDATE seats` w `INSERT seat_hold_requests` f **nafs transaction**. Haka ma kaynch HELD bla recorded idempotency history. Booking `CANCEL_PENDING` w `INSERT booking_cancellation_jobs` f **nafs local transaction**. Inventory release dyal seat w marking reservation history CANCELLED f **nafs transaction**.

**Consistency:** `ck_seats_lifecycle_state` kayفرض:

```text
AVAILABLE => reservation_id, hold_id, hold_customer_id, hold_expires_at = NULL
HELD      => hold_id + customer_id + expires_at NOT NULL; reservation_id NULL
RESERVED  => reservation_id NOT NULL; all hold fields NULL
```

**Isolation:** atomic `UPDATE ... WHERE status = ...` katdir row locking f PostgreSQL. Ila 20 users kaytnafso 3la seat, only one update ymatchi. `FOR UPDATE SKIP LOCKED` f cancellation jobs kaykhlli workers y9essmo rows bla same lease.

**Durability:** booking state and jobs in PostgreSQL persist after process restart; a client HTTP timeout ma kaymhi-ch server-side state.

**SOLID:** `HoldWorkflow` kay3ref business intent, `HoldPort` howa interface, `RestInventoryAdapter` HTTP implementation, `SeatHoldService` handles atomic inventory writes, `ReservationHistoryStore` stores idempotency tombstones, `BookingCancellationWorker` does only scheduled orchestration. Kamlou bhal dependency inversion / separation of concerns.

**Mouhim:** ACID ma kaynch across `booking_db` + `inventory_db`. We use a *Saga-style durable compensation job* and eventual consistency. Chno ila Inventory releases seat walakin worker crashes qbel update Booking? Retry performs idempotent release, then marks CANCELLED. No distributed 2PC assumed.

## 2. Install safely — ma temessch fix dyal Recovery Worker

You recently fixed `BookingRecoveryWorker` with an AI CLI. L patch hna is computed **against Part 7 archive BEFORE your CLI changes**. Important! **Do not copy an old full-project ZIP over your live directory.** Use a unified patch; it should preserve unrelated CLI fixes. If conflict appears, DON'T force apply.

```bash
cd ~/Desktop/seatlock
mkdir -p ~/Desktop/seatlock-backups
cp -a booking-service ~/Desktop/seatlock-backups/booking-before-075
cp -a inventory-service ~/Desktop/seatlock-backups/inventory-before-075
cp -a gateway-service ~/Desktop/seatlock-backups/gateway-before-075

# Extract deliverable ZIP into Downloads, then:
git apply --check ~/Downloads/SeatLock_Part075.patch
# ONLY if --check exits successfully:
git apply ~/Downloads/SeatLock_Part075.patch
```

Alternative (ZIP bundle has an installer which runs the compatibility check, then backs up all three services before applying):

```bash
cd ~/Desktop/seatlock
bash ~/Downloads/SeatLock-Part075-Patch/INSTALL_PART075.sh
```

**If `git apply --check` fails:** your CLI modified overlapping lines. Do NOT overwrite recovery code or apply with `--reject`; give the patch to your CLI agent and ask it to merge hunks carefully while preserving its previous fix. Migrations `V4/V5` Inventory and `V3/V4` Booking are ADDITIVE with historical row backfill; never run `flyway clean` or wipe databases.

### DB migrations

Inventory (after V3):
- `V4__temporary_seat_holds.sql`: adds hold columns, updates both seat CHECK constraints, unique hold_id index, immutable `seat_hold_requests`.
- `V5__reservation_history.sql`: creates permanent ACTIVE/CANCELLED history, **backfills existing RESERVED seats**.

Booking (after V2):
- `V3__booking_hold_reference.sql`: nullable `hold_id` reference on bookings. Old bookings unaffected.
- `V4__cancellation_jobs.sql`: extends status CHECK + durable cancellation jobs.

**Backup DB before migrations** in any environment with valuable data. All tests should first run against disposable/Testcontainers DBs.

## 3. Start services

You already have PostgreSQL + Keycloak from previous parts. Stop old Java processes FIRST and restart one-by-one. Inventory first, Booking second, Gateway third. Ports 8082/8083/8080 must not be occupied by OLD JVMs.

```bash
cd ~/Desktop/seatlock
# Inventory terminal (DB_PASSWORD must be exported):
cd inventory-service
mvn clean verify
mvn spring-boot:run -Dspring-boot.run.profiles=local

# Booking terminal (BOOKING_DB_PASSWORD + BOOKING_MACHINE_SECRET exported):
cd ~/Desktop/seatlock/booking-service
mvn clean verify
export RECOVERY_ENABLED=true
mvn spring-boot:run

# Gateway terminal
cd ~/Desktop/seatlock/gateway-service
mvn clean verify
mvn spring-boot:run
```

**Start one command per terminal; `mvn spring-boot:run` blocks intentionally.**

Verify:

```bash
for port in 8082 8083 8080; do
  curl -fsS "http://localhost:$port/actuator/health" && echo
 done
```

Optional verify Flyway history:

```bash
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d inventory_db -c "SELECT installed_rank,version,success FROM flyway_schema_history ORDER BY installed_rank;"'
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d booking_db -c "SELECT installed_rank,version,success FROM flyway_schema_history ORDER BY installed_rank;"'
```

## 4. Seed new isolated TEST event (no deletions)

You already booked many seats in the original demo event. Ma nresetiwch data. Had script katzid event jdid + 20 seats (additive only):

```bash
cd ~/Desktop/seatlock
bash scripts/part075_seed_event.sh
# Copy result exactly, e.g.:
export SEATLOCK_EVENT_ID=PASTE_NEW_EVENT_UUID
```

Use same terminal export for next Python smoke commands. It uses authenticated CLI demo users. **Keycloak password grant in scripts is local test convenience only, not frontend production auth; frontend should use Authorization Code + PKCE.**

## 5. Lab A — Holds, idempotency, owner checking

```bash
cd ~/Desktop/seatlock
python3 scripts/part075_holds_smoke.py
```

Expected checks:
- Alice creates a hold: 201 HELD, `expiresAt` present.
- Same idempotency key same owner same seat: 200 same `holdId`.
- Bob cannot redeem Alice's hold: 409.
- Bob cannot bypass hold with ordinary booking: 409.
- Alice confirms hold via Booking: 201 CONFIRMED.
- Canceling active hold: 204, seat AVAILABLE.
- After cancellation same old key cannot create another hold: 409 due to tombstone.

Direct inventory hold/confirm endpoints are **machine-only**; don't route them publicly through Gateway.

## 6. Lab B — Confirmed booking cancellation + compensation

```bash
python3 scripts/part075_cancel_smoke.py
```

Expected:
- Alice books AVAILABLE seat → 201 CONFIRMED.
- Bob tries to cancel Alice booking → 404 (ownership hiding).
- Alice `POST /api/v1/bookings/{id}/cancel` → 202 CANCEL_PENDING.
- Cancellation worker claims job; calls Inventory release with **original bookingId**.
- Inventory `RESERVED → AVAILABLE`, `seat_reservation_history.ACTIVE → CANCELLED`, same transaction.
- Booking `CANCEL_PENDING → CANCELLED`; cancellation job becomes DONE.
- A late `/retry` of canceled booking must NOT reserve the seat again.
- Bob can reserve released seat with new Booking ID.

SQL verification (replace Booking UUID):

```sql
SELECT b.id,b.status,c.state,c.attempt_count,c.last_error
FROM bookings b JOIN booking_cancellation_jobs c ON c.booking_id=b.id
WHERE b.id='PUT_REAL_BOOKING_UUID';
-- Expected: CANCELLED / DONE
```

**If Inventory is DOWN:** cancel returns 202, worker retries; job is persisted. Backoff is exponential with capped jitter. After max attempts job becomes `EXHAUSTED` and requires operator intervention; never claim unconditional eventual completion.

## 7. Lab C — Race conditions, ACID correctness

```bash
python3 scripts/part075_concurrency.py --clients 20
```

Expected: on a fresh AVAILABLE seat, 1 request `201 CONFIRMED` and 19 requests `409` (with Gateway Redis rate-limit profile **OFF**). This does not measure max throughput; it tests the **invariant**. In Java tests, `SeatHoldLifecycleIntegrationTest` uses PostgreSQL Testcontainers, including 20 competing holds and cancellation tombstone.

```bash
cd ~/Desktop/seatlock/inventory-service
mvn -Dtest=SeatHoldLifecycleIntegrationTest test
mvn -Dtest=SeatReservationConcurrencyTest test
```

For k6 (optional installation):

```bash
# Use fresh seat UUID from GET /api/v1/events/{SEATLOCK_EVENT_ID}/seats
# Set valid JWT access token in ACCESS_TOKEN; never commit it to Git.
SEAT_ID=REAL_AVAILABLE_SEAT_UUID ACCESS_TOKEN=YOUR_ACCESS_TOKEN \
  EVENT_ID="$SEATLOCK_EVENT_ID" VUS=20 \
  k6 run load/booking-same-seat.js
```

**Warning:** the k6 JS script is a contention test, not a full benchmarking report. Watch total 201 vs 409, p95 latency, CPU, PostgreSQL connections and lock waits. Any 429 is Gateway rate limiting, not proof of seat correctness. Don't publish raw auth tokens.

## 8. Lab D — Nginx load balancing m3a 2 Booking instances

This is optional LOCAL LINUX development lab, **not** production HA. `compose.part075.loadbalancer.yaml` uses `network_mode: host` because Boot services bind `127.0.0.1`, and Nginx listens on host `127.0.0.1:8085`.

- Terminal A: existing Booking `8083` with DB credentials + machine secret.
- Terminal B: second Booking JVM with same DB and secret, different port:

```bash
cd ~/Desktop/seatlock/booking-service
export BOOKING_DB_PASSWORD='YOUR_LOCAL_DB_PASSWORD'
export BOOKING_MACHINE_SECRET='YOUR_LOCAL_KEYCLOAK_MACHINE_SECRET'
export SERVER_PORT=8084
export RECOVERY_ENABLED=true
mvn spring-boot:run
```

Prefer interactive `read -r -s` instead of typing secrets in shell history. Both services use the **same booking_db** by design in this lab. Never run Flyway clean.

```bash
cd ~/Desktop/seatlock
docker compose -f compose.yaml -f compose.part075.loadbalancer.yaml up -d booking-load-balancer
curl -i http://localhost:8085/actuator/health
```

Set `BOOKING_URI=http://127.0.0.1:8085` in Gateway terminal and restart Gateway. Then client uses Gateway as before. Nginx will balance request traffic across Booking `8083` and `8084` (`least_conn`). Check the `X-Upstream-Addr` header by calling Nginx directly. Security is still enforced in both Booking instances.

**What actually makes multi-instance safe?** PostgreSQL unique `(customer_id,idempotency_key)` for Booking, atomic seat UPDATE in Inventory, durable job leases with `SKIP LOCKED` + owner token fencing. An in-memory `synchronized` block in one JVM would NOT work across replicas.

Test with default Gateway (no Redis rate limit) and the Python concurrency lab using a NEW test event. For high-traffic benchmarks: measure throughput, failures, p95, and `pg_stat_activity`/`pg_locks` and tune DB pool sizes; not done automatically here.

## 9. Fresh Part 7 recovery regression (before any new work)

You previously confirmed:

```text
booking b264193c-c8d3-4ca5-9983-87ca307dd6f7 => CONFIRMED
```

Now verify durable job `DONE` (old booking remains untouched):

```bash
docker compose exec -T postgres sh -c \
'psql -U "$POSTGRES_USER" -d booking_db -c "
SELECT b.status,j.state,j.attempt_count,j.last_error
FROM bookings b JOIN booking_recovery_jobs j ON j.booking_id=b.id
WHERE b.id = '\''b264193c-c8d3-4ca5-9983-87ca307dd6f7'\'';
"'
```

Then run NEW failure simulation with `part07_recovery_demo.py` (original demo event) or adapt it to a new test event; verify `202 UNCERTAIN → CONFIRMED` without manual retry and job `DONE`. If AI CLI patched RecoveryJobStore or worker, this Part 7.5 patch **does not replace their code**.

## 10. Operational limits / interview traps

1. HOLD expiration is authoritative in PostgreSQL (`now()`), sweeper cleans due rows; even if sweeper late, atomic reserve can reclaim expired holds.
2. CONFIRMED reservations have NO TTL. They are freed by explicit durable cancellation only.
3. CANCELED booking ID has a durable tombstone and cannot resurrect via late retry.
4. Cancellation is not strongly consistent across 2 DBs; state `CANCEL_PENDING` is intentionally visible until reconciliation.
5. `EXHAUSTED` cancellation needs an operator; automatic reconciliation cannot guarantee liveness if Inventory is unavailable forever.
6. Two Booking JVMs share booking_db but do NOT share in-memory locks. DB CAS, unique indexes, and leases enforce correctness.
7. Redis rate limiting is not the source of truth for seat allocation.
8. User ownership comes from JWT subject, never from client-provided customer ID.
9. No payment or refund workflow exists here. If later adding payments, cancellation MUST coordinate refunds/compensations before becoming production ready.
10. Server-side load test on a single Linux machine is a **lab**, not high-availability proof.

## 11. Recommended validation order

- `git apply --check` first, preserve user's existing recovery fix.
- `mvn clean verify` for Inventory / Booking / Gateway; fix compilation errors before any live migration.
- Run `BookingCancellationIntegrationTest` and `SeatHoldLifecycleIntegrationTest` using disposable PostgreSQL Testcontainers.
- Backup DB, then start Inventory (`V4/V5`), Booking (`V3/V4`), Gateway.
- Check health + Flyway migrations.
- Run `part075_seed_event.sh`, export event ID.
- Run holds smoke, cancellation smoke, Python concurrency test.
- Inspect cancellation job + reservation tombstone SQL.
- Repeat Part 7 recovery scenario to avoid regressions.
- Optional Nginx two-instance, k6 and metrics.

**Do not describe tests as PASS until you actually execute them on Ubuntu.**
