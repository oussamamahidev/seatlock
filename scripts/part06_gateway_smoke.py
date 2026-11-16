#!/usr/bin/env python3
"""Part 6 real HTTP smoke: requires Part 5 Keycloak, Inventory, Booking and Gateway.
Uses DEMO ONLY Keycloak direct access grants for test users; not a frontend auth flow.
"""
import json
import os
import sys
import uuid
import urllib.error
import urllib.parse
import urllib.request

GATEWAY = os.getenv('GATEWAY_URL', 'http://localhost:8080')
KEYCLOAK = os.getenv('KEYCLOAK_URL', 'http://localhost:8090')
INVENTORY = os.getenv('INVENTORY_URL', 'http://localhost:8082')
BOOKING = os.getenv('BOOKING_URL', 'http://localhost:8083')
EVENT = '11111111-1111-4111-8111-111111111111'


def request(url, method='GET', token=None, payload=None, headers=None, form=False):
    headers = dict(headers or {})
    if token:
        headers['Authorization'] = 'Bearer ' + token
    raw = None
    if payload is not None:
        if form:
            raw = urllib.parse.urlencode(payload).encode('utf-8')
            headers['Content-Type'] = 'application/x-www-form-urlencoded'
        else:
            raw = json.dumps(payload).encode('utf-8')
            headers['Content-Type'] = 'application/json'
    req = urllib.request.Request(url, data=raw, method=method, headers=headers)
    try:
        response = urllib.request.urlopen(req, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    except urllib.error.URLError as error:
        raise RuntimeError(f'Network error contacting {url}: {error}') from error
    with response:
        text = response.read().decode('utf-8')
        try:
            data = json.loads(text) if text else None
        except ValueError:
            data = text
        return response.status, data, dict(response.headers.items())


def expect(label, actual, expected):
    if actual != expected:
        raise AssertionError(f'{label}: expected HTTP {expected}, got {actual}')
    print(f'PASS {label}: HTTP {actual}')


def token(username, password):
    status, payload, _ = request(
        KEYCLOAK + '/realms/seatlock/protocol/openid-connect/token',
        'POST', payload={'client_id': 'seatlock-cli', 'grant_type': 'password',
                         'username': username, 'password': password}, form=True)
    expect(f'Keycloak {username} token', status, 200)
    return payload['access_token']


def header(headers, name):
    return next((value for key, value in headers.items() if key.lower() == name.lower()), None)


def run():
    for name, url in [('Inventory', INVENTORY), ('Booking', BOOKING), ('Gateway', GATEWAY)]:
        status, _, _ = request(url + '/actuator/health')
        expect(name + ' health', status, 200)

    alice = token('alice', os.getenv('SEATLOCK_ALICE_PASSWORD', 'dev_only_alice_password'))
    bob = token('bob', os.getenv('SEATLOCK_BOB_PASSWORD', 'dev_only_bob_password'))
    observer = token('observer', os.getenv('SEATLOCK_OBSERVER_PASSWORD', 'dev_only_observer_password'))

    # Routing + public read with user JWT: gateway MUST strip Authorization before inventory.
    status, events, headers = request(GATEWAY + '/api/v1/events?page=0&size=10')
    expect('Public inventory event routing', status, 200)
    assert header(headers, 'X-Request-ID'), 'Gateway did not return correlation ID'
    status, _, _ = request(GATEWAY + f'/api/v1/events/{EVENT}/seats?size=50', token=alice)
    expect('Public inventory read despite customer booking-audience token', status, 200)

    # Controlled correlation ID + input validation.
    status, _, headers = request(GATEWAY + '/api/v1/events', headers={'X-Request-ID': 'seatlock-test-123'})
    expect('Correlation request', status, 200)
    assert header(headers, 'X-Request-ID') == 'seatlock-test-123', headers
    status, _, headers = request(GATEWAY + '/api/v1/events', headers={'X-Request-ID': 'bad value with spaces'})
    expect('Unsafe request ID sanitization', status, 200)
    assert header(headers, 'X-Request-ID') != 'bad value with spaces', headers
    print('PASS request IDs propagated and sanitized')

    # CORS preflight is answered at Gateway, not proxied downstream.
    status, _, headers = request(GATEWAY + '/api/v1/bookings', 'OPTIONS', headers={
        'Origin': 'http://localhost:5173',
        'Access-Control-Request-Method': 'POST',
        'Access-Control-Request-Headers': 'authorization,content-type,idempotency-key'})
    expect('CORS preflight', status, 200)
    assert header(headers, 'Access-Control-Allow-Origin') == 'http://localhost:5173', headers
    print('PASS CORS allows known development frontend')

    fake_body = {'eventId': str(uuid.uuid4()), 'seatId': str(uuid.uuid4())}
    status, _, _ = request(GATEWAY + '/api/v1/bookings', 'POST', payload=fake_body,
                           headers={'X-Demo-User': str(uuid.uuid4()), 'Idempotency-Key': 'spoof-test'})
    expect('Spoofed X-Demo-User rejected', status, 401)
    status, _, _ = request(GATEWAY + '/api/v1/bookings', 'POST', token='not-a-jwt', payload=fake_body,
                           headers={'Idempotency-Key': 'fake-jwt-test'})
    expect('Invalid bearer token rejected', status, 401)
    status, _, _ = request(GATEWAY + '/api/v1/bookings', 'POST', token=observer, payload=fake_body,
                           headers={'Idempotency-Key': 'observer-test'})
    expect('No customer role forbidden', status, 403)

    # Machine-only endpoint is not part of public gateway route table.
    status, seats, _ = request(GATEWAY + f'/api/v1/events/{EVENT}/seats?size=50')
    expect('Public seats through gateway', status, 200)
    seat = next((s['id'] for s in seats['content'] if s['status'] == 'AVAILABLE'), None)
    if seat is None:
        raise AssertionError('No available demo seat: use a fresh test event; do NOT wipe production data')
    internal_url = GATEWAY + f'/api/v1/events/{EVENT}/seats/{seat}/reservations'
    status, _, _ = request(internal_url, 'POST', token=alice,
                           payload={'bookingId': str(uuid.uuid4())})
    expect('Direct customer inventory reservation blocked', status, 403)
    print('PASS internal inventory write not publicly exposed')

    key = 'part06-' + str(uuid.uuid4())
    body = {'eventId': EVENT, 'seatId': seat}
    status, created, _ = request(GATEWAY + '/api/v1/bookings', 'POST', token=alice,
                                 payload=body, headers={'Idempotency-Key': key})
    expect('Alice creates booking THROUGH gateway', status, 201)
    assert created['status'] == 'CONFIRMED', created
    booking_id = created['id']
    status, replay, _ = request(GATEWAY + '/api/v1/bookings', 'POST', token=alice,
                                payload=body, headers={'Idempotency-Key': key})
    expect('Idempotent replay through gateway', status, 200)
    assert replay['id'] == booking_id, 'Idempotency key returned a different booking'
    status, _, _ = request(GATEWAY + f'/api/v1/bookings/{booking_id}', token=bob)
    expect('Bob cannot read Alice booking', status, 404)
    status, got, _ = request(GATEWAY + f'/api/v1/bookings/{booking_id}', token=alice)
    expect('Alice reads her booking', status, 200)
    assert got['id'] == booking_id, 'Booking response mismatch'

    print('\nALL PART 6 GATEWAY SMOKE TESTS PASSED')


if __name__ == '__main__':
    try:
        run()
    except (AssertionError, RuntimeError, KeyError, TypeError) as error:
        print('FAIL:', error, file=sys.stderr)
        sys.exit(1)
