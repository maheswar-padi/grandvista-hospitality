// k6 load test for GrandVista. Run on the Docker network so it goes through the gateway load balancer:
//   docker run --rm --network grandvista -v "$PWD/load/k6:/scripts" -v "$PWD/docs/testing/evidence:/out" \
//     grafana/k6:1.1.0 run /scripts/load-test.js
// The gateways' per-IP rate limits must be raised for the run (see scripts/load-test-k6.sh, which does this and
// restores them). Two scenarios:
//   browse     — ramp to 20 virtual users searching rooms and pricing a stay (read path, dynamic pricing)
//   contention — 10 customers try to book the same room for the same nights at the same moment
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://gateway-lb:8080';
const API = `${BASE}/api/v1`;
const accepted = new Counter('contention_bookings_accepted');
const refused = new Counter('contention_bookings_refused');
const unexpected = new Counter('contention_unexpected_status');

http.setResponseCallback(http.expectedStatuses(200, 201, 409));

export const options = {
  scenarios: {
    browse: {
      executor: 'ramping-vus',
      exec: 'browse',
      startVUs: 0,
      stages: [
        { duration: '10s', target: 20 },
        { duration: '40s', target: 20 },
        { duration: '5s', target: 0 },
      ],
    },
    contention: {
      executor: 'per-vu-iterations',
      exec: 'contend',
      vus: 10,
      iterations: 1,
      startTime: '20s',
      maxDuration: '30s',
    },
  },
  thresholds: {
    'http_req_duration{scenario:browse}': ['p(95)<500'],
    contention_bookings_accepted: ['count==1'],
    contention_unexpected_status: ['count==0'],
    // declared so that per-request-type latencies appear in the summary
    'http_req_duration{name:search}': ['p(95)<1000'],
    'http_req_duration{name:quote}': ['p(95)<1000'],
    'http_req_duration{name:contended-booking}': ['max<15000'],
    'http_req_failed{scenario:browse}': ['rate<0.01'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

function day(offset) {
  const d = new Date(Date.now() + offset * 86400000);
  return d.toISOString().slice(0, 10);
}

export function setup() {
  const offset = 100 + Math.floor(Math.random() * 200);
  const checkIn = day(offset);
  const checkOut = day(offset + 2);
  const rooms = http.get(`${API}/rooms?checkIn=${checkIn}&checkOut=${checkOut}&guests=2&size=50`).json('content');
  const tokens = [];
  for (let i = 0; i < 10; i++) {
    const res = http.post(`${API}/auth/register`, JSON.stringify({
      email: `k6-${Date.now()}-${i}-${Math.floor(Math.random() * 1e6)}@example.test`,
      password: 'correct-horse-42', fullName: `K6 Guest ${i}`,
    }), { headers: { 'Content-Type': 'application/json' } });
    tokens.push(res.json('accessToken'));
  }
  return { checkIn, checkOut, roomIds: rooms.map((r) => r.id), contested: rooms[0].id, tokens };
}

export function browse(data) {
  const search = http.get(`${API}/rooms?checkIn=${data.checkIn}&checkOut=${data.checkOut}&guests=2&size=12`,
    { tags: { name: 'search' } });
  check(search, { 'search 200': (r) => r.status === 200 });
  const roomId = data.roomIds[Math.floor(Math.random() * data.roomIds.length)];
  const quote = http.get(`${API}/rooms/${roomId}/quote?checkIn=${data.checkIn}&checkOut=${data.checkOut}&guests=2`,
    { tags: { name: 'quote' } });
  check(quote, { 'quote 200': (r) => r.status === 200 });
  sleep(0.2);
}

export function contend(data) {
  const token = data.tokens[__VU - 1] || data.tokens[0];
  const res = http.post(`${API}/bookings`, JSON.stringify({
    roomId: data.contested, checkIn: data.checkIn, checkOut: data.checkOut, guests: 2,
    primaryGuest: { fullName: 'K6 Guest', email: 'k6@example.test' },
  }), {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}`, 'Idempotency-Key': `k6-${__VU}-${Date.now()}` },
    tags: { name: 'contended-booking' },
  });
  if (res.status === 201) accepted.add(1);
  else if (res.status === 409) refused.add(1);
  else unexpected.add(1);
}

export function handleSummary(data) {
  const out = __ENV.SUMMARY_DIR || '/out';
  return {
    [`${out}/k6-summary.json`]: JSON.stringify(data, null, 2),
    stdout: textSummary(data),
  };
}

function textSummary(data) {
  const m = data.metrics;
  const t = (name) => m[name] ? m[name].values : {};
  const fmt = (v) => (v === undefined ? '-' : v.toFixed(1));
  const lines = [];
  lines.push('GrandVista k6 load test');
  lines.push(`requests total: ${t('http_reqs').count} (${fmt(t('http_reqs').rate)} req/s overall)`);
  for (const name of ['search', 'quote', 'contended-booking']) {
    const key = Object.keys(m).find((k) => k === `http_req_duration{name:${name}}`);
    if (key) {
      const v = m[key].values;
      lines.push(`${name}: avg ${fmt(v.avg)} ms, median ${fmt(v.med)} ms, p95 ${fmt(v['p(95)'])} ms, p99 ${fmt(v['p(99)'])} ms, max ${fmt(v.max)} ms`);
    }
  }
  lines.push(`browse failure rate: ${(t('http_req_failed{scenario:browse}').rate * 100 || 0).toFixed(2)} %`);
  lines.push(`contention: ${t('contention_bookings_accepted').count || 0} accepted, ${t('contention_bookings_refused').count || 0} refused (409), ${t('contention_unexpected_status').count || 0} unexpected`);
  const failed = Object.entries(m).filter(([, v]) => v.thresholds && Object.values(v.thresholds).some((x) => !x.ok)).map(([k]) => k);
  lines.push(failed.length ? `THRESHOLDS FAILED: ${failed.join(', ')}` : 'all thresholds passed');
  return lines.join('\n') + '\n';
}
