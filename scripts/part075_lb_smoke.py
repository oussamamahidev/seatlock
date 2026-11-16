#!/usr/bin/env python3
"""SeatLock two-replica integration test: Nginx distribution + cross-instance idempotency.
Requires Booking :8083/:8084, Nginx :8085, Inventory :8082, Gateway :8080,
and explicit isolated SEATLOCK_EVENT_ID. Consumes two AVAILABLE test seats.
"""
import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
import os
import sys
import threading
import time
import uuid
from part06_gateway_smoke import request, token, header


def seats_from_gateway(gateway, event):
    status, data, _ = request(f'{gateway}/api/v1/events/{event}/seats?size=50')
    if status != 200 or not isinstance(data, dict):
        raise RuntimeError(f'Failed listing seats via Gateway (HTTP {status})')
    return [x['id'] for x in data['content'] if x['status'] == 'AVAILABLE']


def post(base, access, event, seat, key):
    return request(base.rstrip('/') + '/api/v1/bookings', 'POST', token=access,
                   headers={'Idempotency-Key': key},
                   payload={'eventId': event, 'seatId': seat})


def poll_confirmed(base, access, booking_id):
    for _ in range(15):
        status, booking, _ = request(f'{base}/api/v1/bookings/{booking_id}', token=access)
        if status == 200 and isinstance(booking, dict) and booking.get('status') == 'CONFIRMED':
            return
        time.sleep(1)
    raise AssertionError(f'Booking {booking_id} did not become CONFIRMED via {base}')


def run(args):
    event = args.event_id or os.getenv('SEATLOCK_EVENT_ID')
    if not event:
        raise RuntimeError('Export a NEW SEATLOCK_EVENT_ID from scripts/part075_seed_event.sh')
    event = str(uuid.UUID(event))
    lb = args.lb.rstrip('/')
    gateway = args.gateway.rstrip('/')
    direct = [args.replica_a.rstrip('/'), args.replica_b.rstrip('/')]
    access = token('alice', os.getenv('SEATLOCK_ALICE_PASSWORD', 'dev_only_alice_password'))
    for name, url in [('Booking A', direct[0]), ('Booking B', direct[1]), ('Gateway', gateway)]:
        status, _, _ = request(url + '/actuator/health')
        if status != 200:
            raise RuntimeError(f'{name} not UP (HTTP {status})')
        print('PASS', name, 'health:', status)

    # Proof: actually see different Nginx upstream addresses, not just Nginx 200.
    seen = Counter()
    for _ in range(30):
        status, _, headers = request(lb + '/actuator/health')
        if status != 200:
            raise RuntimeError(f'Nginx health proxy HTTP {status}; check both replicas')
        upstream = header(headers, 'X-Upstream-Addr')
        if not upstream:
            raise AssertionError('Nginx must expose X-Upstream-Addr in the local demo')
        seen[upstream] += 1
    print('Nginx selected upstreams:', dict(seen))
    if not any('8083' in u for u in seen) or not any('8084' in u for u in seen):
        raise AssertionError('Traffic was not observed on both 8083 and 8084; check Nginx')
    print('PASS: Nginx forwarded traffic to BOTH Booking replicas')

    free = seats_from_gateway(gateway, event)
    if len(free) < 2:
        raise RuntimeError('Need >= 2 AVAILABLE seats; run seeding script, do not delete existing data')
    idempotency_seat, contested_seat = free[:2]
    key = 'cross-replica-' + str(uuid.uuid4())
    barrier = threading.Barrier(2)

    # Same user + same key via TWO independent JVMs: must produce one logical booking.
    def once(idx):
        barrier.wait(timeout=15)
        status, data, headers = post(direct[idx], access, event, idempotency_seat, key)
        return (status, data, headers)

    with ThreadPoolExecutor(max_workers=2) as pool:
        f1 = pool.submit(once, 0)
        f2 = pool.submit(once, 1)
        two = [f1.result(timeout=30), f2.result(timeout=30)]
    print('Same-key response codes across JVMs:', [r[0] for r in two])
    if any(s not in (200, 201) for s, _, _ in two) or sum(s == 201 for s, _, _ in two) != 1:
        raise AssertionError(f'Cross-JVM idempotency failure (expect one 201, one 200): {[r[0] for r in two]}')
    ids = {data.get('id') for _, data, _ in two if isinstance(data, dict)}
    if len(ids) != 1 or None in ids:
        raise AssertionError(f'Cross-JVM requests returned different bookings: {ids}')
    booking_id = ids.pop()
    for url in direct:
        poll_confirmed(url, access, booking_id)
    print('PASS: same key through 2 JVMs -> SAME booking ID; visible as CONFIRMED on both')

    # Distinct keys / same seat through the load balancer.
    barrier = threading.Barrier(args.clients)
    run_key = str(uuid.uuid4())

    def race(i):
        try:
            barrier.wait(timeout=60)
            status, body, headers = post(lb, access, event, contested_seat, f'lb-{run_key}-{i}')
            return status, body, header(headers, 'X-Upstream-Addr')
        except Exception as ex:
            return -1, {'error': str(ex)}, None

    with ThreadPoolExecutor(max_workers=args.clients) as pool:
        futures = [pool.submit(race, i) for i in range(args.clients)]
        results = [f.result(timeout=90) for f in futures]
    counts = Counter(s for s, _, _ in results)
    upstreams = Counter(u for _, _, u in results if u)
    print('Contested seat HTTP statuses via Nginx:', dict(counts))
    print('Contested seat upstreams:', dict(upstreams))
    winners = [data for s, data, _ in results if s == 201 and isinstance(data, dict) and data.get('status') == 'CONFIRMED']
    if len(winners) != 1 or counts.get(201, 0) != 1 or counts.get(409, 0) != args.clients-1:
        raise AssertionError('Expected one 201 CONFIRMED and all other requests 409; inspect overload errors')
    winner_id = winners[0]['id']
    for url in direct:
        poll_confirmed(url, access, winner_id)
    inventory_status, inventory_body, _ = request(
        f'{gateway}/api/v1/events/{event}/seats?size=50')
    matching_seat = next((x for x in inventory_body.get('content', [])
                          if x.get('id') == contested_seat), None) if isinstance(inventory_body, dict) else None
    if inventory_status != 200 or not matching_seat or matching_seat.get('status') != 'RESERVED':
        raise AssertionError(f'Inventory seat not RESERVED after confirmed winner: {matching_seat}')

    # Full path validation: Gateway must use BOOKING_URI=Nginx:8085, not bypass LB.
    gateway_upstreams = Counter()
    for _ in range(20):
        status, data, headers = request(
            f'{gateway}/api/v1/bookings/{winner_id}', token=access)
        if status != 200 or not isinstance(data, dict) or data.get('id') != winner_id:
            raise AssertionError(f'Gateway booking read failed after load balancing: HTTP {status}')
        upstream = header(headers, 'X-Upstream-Addr')
        if not upstream:
            raise AssertionError('Gateway did not forward Nginx X-Upstream-Addr: check BOOKING_URI at Gateway startup')
        gateway_upstreams[upstream] += 1
    print('Gateway -> Nginx upstreams:', dict(gateway_upstreams))
    if not any('8083' in u for u in gateway_upstreams) or not any('8084' in u for u in gateway_upstreams):
        raise AssertionError('Gateway traffic did not reach BOTH Booking replicas through Nginx')
    print('PASS: Gateway -> Nginx -> both replicas is proven with real authenticated reads')
    print(f'PASS: {args.clients} requests through Nginx -> 1 CONFIRMED, {args.clients-1} conflicts')
    print('PASS: BOTH Booking replicas read same confirmed winner from shared PostgreSQL')
    print('ALL MULTI-INSTANCE TESTS PASSED')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--clients', type=int, default=40)
    p.add_argument('--event-id')
    p.add_argument('--gateway', default=os.getenv('GATEWAY_URL', 'http://127.0.0.1:8080'))
    p.add_argument('--lb', default=os.getenv('BOOKING_LB_URL', 'http://127.0.0.1:8085'))
    p.add_argument('--replica-a', default='http://127.0.0.1:8083')
    p.add_argument('--replica-b', default='http://127.0.0.1:8084')
    a = p.parse_args()
    if not 2 <= a.clients <= 100:
        p.error('--clients must be 2..100')
    try:
        run(a)
    except (AssertionError, RuntimeError, ValueError, TimeoutError, OSError, KeyError) as e:
        print('FAIL:', e, file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
