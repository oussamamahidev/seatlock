#!/usr/bin/env python3
"""Dev-only failure/recovery lab. `pick` while Inventory UP, `create` when DOWN, `watch` after UP.
Uses Keycloak demo password grant only for local testing, not for production clients.
"""
import argparse
import os
import sys
import time
import uuid
from part06_gateway_smoke import GATEWAY, EVENT, request, token


def alice_token():
    return token('alice', os.getenv('SEATLOCK_ALICE_PASSWORD', 'dev_only_alice_password'))


def pick():
    status, payload, _ = request(GATEWAY + f'/api/v1/events/{EVENT}/seats?size=50')
    if status != 200:
        raise RuntimeError(f'Inventory not available to pick seat: HTTP {status}')
    seats = [s['id'] for s in payload.get('content', []) if s.get('status') == 'AVAILABLE']
    if not seats:
        raise RuntimeError('No available seats in demo event; do not reset database blindly')
    print('\nAvailable seat (save this UUID before stopping Inventory):', seats[0])


def create(seat):
    booking_key = 'part07-' + str(uuid.uuid4())
    status, data, _ = request(GATEWAY + '/api/v1/bookings', 'POST', token=alice_token(),
                             payload={'eventId': EVENT, 'seatId': seat},
                             headers={'Idempotency-Key': booking_key})
    print('HTTP:', status, 'Booking:', data)
    if status != 202 or not isinstance(data, dict) or data.get('status') != 'UNCERTAIN':
        raise AssertionError('Expected HTTP 202 UNCERTAIN while Inventory is stopped')
    print('SAVE BOOKING_ID:', data['id'])
    print('Start Inventory; then use: python3 scripts/part07_recovery_demo.py watch', data['id'])


def watch(booking_id):
    bearer = alice_token()
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        status, data, _ = request(GATEWAY + '/api/v1/bookings/' + booking_id, token=bearer)
        if status != 200:
            raise RuntimeError(f'Booking GET failed: HTTP {status}: {data}')
        state = data.get('status')
        print('booking', booking_id, 'status:', state)
        if state in ('CONFIRMED', 'REJECTED'):
            print('PASS: automatic recovery reached a terminal state:', state)
            return
        time.sleep(3)
    raise TimeoutError('Recovery did not reach terminal state in 180s; check worker logs and recovery_jobs')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='action', required=True)
    sub.add_parser('pick')
    c = sub.add_parser('create')
    c.add_argument('seat_id')
    w = sub.add_parser('watch')
    w.add_argument('booking_id')
    args = parser.parse_args()
    try:
        if args.action == 'pick': pick()
        if args.action == 'create': create(str(uuid.UUID(args.seat_id)))
        if args.action == 'watch': watch(str(uuid.UUID(args.booking_id)))
    except (RuntimeError, AssertionError, TimeoutError, KeyError, ValueError) as exc:
        print('FAIL:', exc, file=sys.stderr)
        sys.exit(1)
