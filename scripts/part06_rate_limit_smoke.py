#!/usr/bin/env python3
"""Optional: run only when Redis is on and gateway SPRING_PROFILES_ACTIVE=rate-limit."""
from concurrent.futures import ThreadPoolExecutor
from collections import Counter
import os
import uuid
from part06_gateway_smoke import GATEWAY, request, token

auth = token('alice', os.getenv('SEATLOCK_ALICE_PASSWORD', 'dev_only_alice_password'))

def attempt(_):
    status, _, _ = request(GATEWAY + '/api/v1/bookings/' + str(uuid.uuid4()), token=auth)
    return status

with ThreadPoolExecutor(max_workers=40) as pool:
    counts = Counter(pool.map(attempt, range(100)))
print('Rate limit test HTTP status counts:', dict(counts))
assert counts[429] > 0, 'No 429 observed: confirm gateway profile rate-limit is active and Redis running'
assert not counts[500], 'Internal failures: check Redis and gateway logs'
print('PASS: Redis-backed token bucket returned 429 under burst')
