#!/usr/bin/env python3
"""SeatLock correctness-under-contention lab (not a capacity benchmark).
100 independent authenticated requests attempt the SAME AVAILABLE seat.
Requires an explicitly selected isolated test event; reserves one seat permanently.
Do not enable Gateway Redis rate-limit for this correctness test.
"""
import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
import math
import os
import pathlib
import sys
import threading
import time
import uuid

from part06_gateway_smoke import request, token


def percentile(values, pct):
    if not values:
        return None
    ordered = sorted(values)
    return round(ordered[max(0, math.ceil((pct / 100) * len(ordered)) - 1)], 2)


def selected_seat(base, event, explicit_seat):
    status, body, _ = request(f'{base}/api/v1/events/{event}/seats?size=50')
    if status != 200 or not isinstance(body, dict):
        raise RuntimeError(f'Cannot list event seats (HTTP {status}); verify Gateway and event id')
    seats = body.get('content', [])
    if not isinstance(seats, list):
        raise RuntimeError('Unexpected JSON seat list')
    eligible = [s for s in seats if s.get('status') == 'AVAILABLE']
    if explicit_seat:
        if explicit_seat not in {s.get('id') for s in eligible}:
            raise RuntimeError('Selected seat is not AVAILABLE in chosen test event')
        return explicit_seat
    if not eligible:
        raise RuntimeError('No AVAILABLE seat; run scripts/part075_seed_event.sh first')
    return eligible[0]['id']


def result_is_winner(result):
    body = result.get('body')
    return result.get('status') == 201 and isinstance(body, dict) and body.get('status') == 'CONFIRMED'


def run(args):
    event = args.event_id or os.getenv('SEATLOCK_EVENT_ID')
    if not event:
        raise RuntimeError('Missing explicit --event-id or SEATLOCK_EVENT_ID; do not use shared demo seats')
    event = str(uuid.UUID(event))
    base = args.url.rstrip('/')
    code, _, _ = request(base + '/actuator/health')
    if code != 200:
        raise RuntimeError(f'Gateway health HTTP {code}; start it before load testing')
    access = token('alice', os.getenv('SEATLOCK_ALICE_PASSWORD', 'dev_only_alice_password'))
    seat = selected_seat(base, event, args.seat_id)
    print(f'Using isolated event {event}, seat {seat}, clients={args.clients}')
    print('WARNING: this deliberately reserves exactly one TEST seat')
    gate = threading.Barrier(args.clients)
    tag = str(uuid.uuid4())

    def one(i):
        # All threads block here until the last one arrives.
        try:
            gate.wait(timeout=60)
        except threading.BrokenBarrierError:
            return {'status': -1, 'error': 'Client barrier broken', 'elapsed_ms': 0.0}
        start = time.perf_counter()
        try:
            status, body, headers = request(
                base + '/api/v1/bookings', 'POST', token=access,
                payload={'eventId': event, 'seatId': seat},
                headers={'Idempotency-Key': f'race-{tag}-{i}'})
            return {'status': status, 'body': body,
                    'request_id': next((v for k, v in headers.items() if k.lower() == 'x-request-id'), None),
                    'elapsed_ms': round((time.perf_counter() - start) * 1000, 2)}
        except Exception as exc:
            return {'status': -1, 'error': str(exc),
                    'elapsed_ms': round((time.perf_counter() - start) * 1000, 2)}

    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=args.clients) as pool:
        futures = [pool.submit(one, i) for i in range(args.clients)]
        results = [f.result(timeout=90) for f in futures]
    elapsed = time.perf_counter() - started
    counts = Counter(r['status'] for r in results)
    winners = [r for r in results if result_is_winner(r)]
    ms = [r['elapsed_ms'] for r in results]
    report = {
        'timestamp_utc': datetime.now(timezone.utc).isoformat(),
        'event_id': event, 'seat_id': seat,
        'target': base, 'clients': args.clients,
        'status_counts': {str(k): v for k, v in sorted(counts.items())},
        'confirmed_winners': len(winners),
        'elapsed_seconds': round(elapsed, 3),
        'requests_per_second': round(len(results) / max(elapsed, 0.001), 2),
        'latency_ms': {'min': round(min(ms), 2), 'p50': percentile(ms, 50),
                       'p95': percentile(ms, 95), 'p99': percentile(ms, 99),
                       'max': round(max(ms), 2)},
        'errors': [{'status': r['status'], 'error': r.get('error'), 'request_id': r.get('request_id')}
                   for r in results if r['status'] not in (201, 409)][:15],
    }
    # Always check authoritative Inventory state before declaring PASS.
    status, body, _ = request(f'{base}/api/v1/events/{event}/seats?size=50')
    matches = body.get('content', []) if status == 200 and isinstance(body, dict) else []
    current = next((s for s in matches if s.get('id') == seat), None)
    inventory_state = current.get('status') if current else None
    passed = (status == 200 and inventory_state == 'RESERVED'
              and len(winners) == 1 and counts.get(201, 0) == 1
              and counts.get(409, 0) == args.clients - 1)
    report['inventory_seat_status'] = inventory_state
    report['passed'] = bool(passed)
    print(json.dumps(report, indent=2))
    if args.json_out:
        path = pathlib.Path(args.json_out)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
        print('Saved evidence:', path)
    if not passed:
        raise AssertionError('Not PASS: expected exactly 1 CONFIRMED/201, N-1 HTTP 409 and Inventory RESERVED; inspect report')
    print(f'PASS: 1 CONFIRMED / {args.clients-1} conflicts / Inventory RESERVED')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--clients', type=int, default=100, help='2..150; start small if hardware is limited')
    p.add_argument('--url', default=os.getenv('GATEWAY_URL', 'http://127.0.0.1:8080'))
    p.add_argument('--event-id', help='isolated event UUID (also SEATLOCK_EVENT_ID)')
    p.add_argument('--seat-id', help='explicit AVAILABLE seat UUID (optional)')
    p.add_argument('--json-out', help='optional local JSON evidence file (no tokens stored)')
    args = p.parse_args()
    if not 2 <= args.clients <= 150:
        p.error('--clients must be between 2 and 150')
    try:
        run(args)
    except (RuntimeError, AssertionError, TimeoutError, OSError, ValueError, KeyError) as e:
        print('FAIL:', e, file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
