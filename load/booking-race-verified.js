// k6 verified-outcome version. A correctness contention test, NOT a throughput benchmark.
// User must provide EVENT_ID, SEAT_ID, ACCESS_TOKEN; use only a fresh demo seat.
import http from 'k6/http';
import { Counter, Rate } from 'k6/metrics';

// 409 = business conflict and is expected in SAME-seat contention.
http.setResponseCallback(http.expectedStatuses(201, 409));
const created = new Counter('seatlock_created');
const conflicts = new Counter('seatlock_conflicts');
const valid = new Rate('seatlock_valid_response');

export const options = {
  scenarios: {
    race: {
      executor: 'per-vu-iterations',
      vus: Number(__ENV.VUS || 100),
      iterations: 1,
      maxDuration: '90s',
    },
  },
  thresholds: {
    seatlock_created: ['count > 0', 'count < 2'],
    seatlock_valid_response: ['rate > 0.999'],
    http_req_failed: ['rate < 0.001'],
    // No p95 assertion by default: measure before choosing a realistic SLO.
  },
};

export default function () {
  const eventId = __ENV.EVENT_ID;
  const seatId = __ENV.SEAT_ID;
  const access = __ENV.ACCESS_TOKEN;
  if (!eventId || !seatId || !access) {
    throw new Error('Set EVENT_ID, SEAT_ID, ACCESS_TOKEN for an isolated test event.');
  }
  const base = __ENV.GATEWAY_URL || 'http://127.0.0.1:8080';
  const response = http.post(`${base}/api/v1/bookings`, JSON.stringify({eventId, seatId}), {
    headers: {
      'Authorization': `Bearer ${access}`,
      'Content-Type': 'application/json',
      'Idempotency-Key': `k6-race-${__VU}-${__ITER}-${Date.now()}`,
    },
  });
  if (response.status === 201) {
    // A bare 201 is not enough: we also inspect CONFIRMED state.
    let body;
    try { body = response.json(); } catch (_) { body = null; }
    if (body && body.status === 'CONFIRMED') { created.add(1); valid.add(true); }
    else valid.add(false);
  } else if (response.status === 409) {
    conflicts.add(1); valid.add(true);
  } else {
    valid.add(false);
  }
}
