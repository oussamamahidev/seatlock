#!/usr/bin/env python3
"""Real HTTP concurrency smoke test using Python standard library only."""
import concurrent.futures
import json
import sys
import threading
import urllib.error
import urllib.request
import uuid

BASE = 'http://127.0.0.1:8082/api/v1/events'
EVENT = '11111111-1111-4111-8111-111111111111'


def get_json(url):
    with urllib.request.urlopen(url, timeout=15) as r:
        return json.load(r)


def post(seat_id, booking_id, barrier):
    barrier.wait(timeout=15)
    data = json.dumps({'bookingId': booking_id}).encode('utf-8')
    req = urllib.request.Request(
        f'{BASE}/{EVENT}/seats/{seat_id}/reservations',
        data=data, method='POST', headers={'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            return response.status, booking_id, response.read().decode()
    except urllib.error.HTTPError as err:
        return err.code, booking_id, err.read().decode()


def main():
    seats = get_json(f'{BASE}/{EVENT}/seats?page=0&size=50')['content']
    free = next((s for s in seats if s['status'] == 'AVAILABLE'), None)
    if free is None:
        print('No AVAILABLE seat! Use a fresh seeded database or add a new seat.')
        sys.exit(2)
    seat_id = free['id']
    barrier = threading.Barrier(20)
    booking_ids = [str(uuid.uuid4()) for _ in range(20)]
    with concurrent.futures.ThreadPoolExecutor(max_workers=20) as pool:
        futures = [pool.submit(post, seat_id, b, barrier) for b in booking_ids]
        results = [f.result(timeout=45) for f in futures]

    counts = {code: sum(r[0] == code for r in results) for code in {r[0] for r in results}}
    print(f'Seat: {free["label"]} ({seat_id})')
    print(f'HTTP status counts: {counts}')
    successes = [r for r in results if r[0] == 201]
    if counts != {201: 1, 409: 19}:
        for code, booking_id, body in results:
            print(f'{code} booking={booking_id} body={body[:400]}')
        sys.exit('FAIL: expected exactly 1×201 and 19×409')
    winner = successes[0][1]
    retry = post(seat_id, winner, threading.Barrier(1))
    print(f'Idempotent retry: HTTP {retry[0]} (expected 200)')
    if retry[0] != 200:
        sys.exit('FAIL: same bookingId should return 200')
    print('PASS: one winner, 19 conflicts, retry idempotent')


if __name__ == '__main__':
    main()
