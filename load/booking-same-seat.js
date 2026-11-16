// Optional k6 load test. Supply GATEWAY_URL, ACCESS_TOKEN and SEAT_ID.
// Each VU sends unique idempotency key to test server-side seat contention.
// Run ONLY with an AVAILABLE seat on a disposable test event; it permanently reserves it.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

export const outcomes = new Counter('seatlock_booking_outcomes');
export const options = {
  scenarios: {
    race: { executor: 'per-vu-iterations', vus: Number(__ENV.VUS || 20), iterations: 1,
            maxDuration: '60s' },
  },
  thresholds: { http_req_failed: ['rate<0.99'] },
};

export default function () {
  const base = __ENV.GATEWAY_URL || 'http://localhost:8080';
  const eventId = __ENV.EVENT_ID || '11111111-1111-4111-8111-111111111111';
  const seatId = __ENV.SEAT_ID;
  if (!seatId || !__ENV.ACCESS_TOKEN) throw new Error('Missing SEAT_ID or ACCESS_TOKEN');
  const response = http.post(`${base}/api/v1/bookings`, JSON.stringify({eventId,seatId}), {
    headers: {'Content-Type':'application/json','Authorization':`Bearer ${__ENV.ACCESS_TOKEN}`,
              'Idempotency-Key': `k6-${__VU}-${__ITER}-${Date.now()}`},
    tags: {scenario:'same-seat'},
  });
  outcomes.add(1, {status: String(response.status)});
  check(response, {'expected conflict or success': r => [201,409].includes(r.status)});
}
