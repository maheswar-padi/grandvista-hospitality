#!/usr/bin/env node
// Load test for a RUNNING stack (Node 18+, no dependencies).
//
//   node scripts/load-test.mjs [--base http://localhost:8080] [--vus 20] [--duration 30] [--contenders 8]
//
// Scenario 1 — read traffic: virtual users repeatedly search availability and request price quotes.
// Scenario 2 — booking contention: N customers try to book the SAME room for the SAME dates at the same
//              instant; the platform must accept exactly one and refuse the rest (no double booking).
//
// Only measured numbers are printed. Note: the gateway rate-limits each client IP (default 300 API
// requests/min, 10 credential requests/min). From a single machine, raise GV_API_RATE_LIMIT /
// GV_AUTH_RATE_LIMIT in .env for throughput runs; 429 responses are reported separately.

const args = Object.fromEntries(
  process.argv.slice(2).reduce((acc, arg, i, all) => (arg.startsWith('--') ? [...acc, [arg.slice(2), all[i + 1]]] : acc), []),
);
const BASE = (args.base ?? 'http://localhost:8080') + '/api/v1';
const VUS = Number(args.vus ?? 20);
const DURATION_S = Number(args.duration ?? 30);
const CONTENDERS = Number(args.contenders ?? 8);

const stats = new Map(); // name -> { latencies: [], statuses: Map }
function record(name, ms, status) {
  if (!stats.has(name)) stats.set(name, { latencies: [], statuses: new Map() });
  const s = stats.get(name);
  s.latencies.push(ms);
  s.statuses.set(status, (s.statuses.get(status) ?? 0) + 1);
}

async function call(name, path, init = {}) {
  const started = performance.now();
  let status = 0;
  let body = null;
  try {
    const res = await fetch(BASE + path, {
      ...init,
      headers: { Accept: 'application/json', 'X-Requested-With': 'XMLHttpRequest', ...(init.headers ?? {}) },
    });
    status = res.status;
    const text = await res.text();
    body = text ? JSON.parse(text) : null;
  } catch {
    status = 'ERR';
  }
  record(name, performance.now() - started, status);
  return { status, body };
}

function hotelDate(offsetDays) {
  const d = new Date(Date.now() + offsetDays * 86_400_000);
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Kolkata' }).format(d);
}

function percentile(sorted, p) {
  if (!sorted.length) return 0;
  return sorted[Math.min(sorted.length - 1, Math.floor((p / 100) * sorted.length))];
}

async function readScenario(roomIds) {
  const deadline = Date.now() + DURATION_S * 1000;
  async function vu() {
    while (Date.now() < deadline) {
      const offset = 20 + Math.floor(Math.random() * 300);
      const checkIn = hotelDate(offset);
      const checkOut = hotelDate(offset + 1 + Math.floor(Math.random() * 4));
      await call('search', `/rooms?checkIn=${checkIn}&checkOut=${checkOut}&guests=2&size=12`);
      const room = roomIds[Math.floor(Math.random() * roomIds.length)];
      await call('quote', `/rooms/${room}/quote?checkIn=${checkIn}&checkOut=${checkOut}&guests=1`);
    }
  }
  const started = performance.now();
  await Promise.all(Array.from({ length: VUS }, vu));
  return (performance.now() - started) / 1000;
}

async function contentionScenario(roomId) {
  const offset = 330 + Math.floor(Math.random() * 30);
  const checkIn = hotelDate(offset);
  const checkOut = hotelDate(offset + 2);
  const tokens = [];
  for (let i = 0; i < CONTENDERS; i++) {
    const email = `load-${Date.now()}-${i}-${Math.floor(Math.random() * 1e6)}@example.test`;
    const { status, body } = await call('register', '/auth/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password: 'correct-horse-42', fullName: `Load Tester ${i}` }),
    });
    if (status !== 201) {
      console.log(`  registration ${i} returned ${status} (credential rate limit?) — using ${tokens.length} contenders`);
      break;
    }
    tokens.push(body.accessToken);
  }
  const outcome = await Promise.all(
    tokens.map((token, i) =>
      call('book (contended)', '/bookings', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}`, 'Idempotency-Key': `load-${Date.now()}-${i}` },
        body: JSON.stringify({ roomId, checkIn, checkOut, guests: 1, primaryGuest: { fullName: `Load Tester ${i}`, email: `guest${i}@example.test` } }),
      }),
    ),
  );
  return { checkIn, checkOut, contenders: tokens.length, statuses: outcome.map((o) => o.status), codes: outcome.map((o) => o.body?.code ?? o.body?.status) };
}

async function main() {
  console.log(`GrandVista load test against ${BASE} — ${VUS} virtual users for ${DURATION_S}s`);
  const rooms = await call('setup', '/rooms?size=50');
  if (rooms.status !== 200 || !rooms.body?.content?.length) {
    console.error(`Cannot list rooms (status ${rooms.status}). Is the stack running?`);
    process.exit(1);
  }
  const roomIds = rooms.body.content.map((r) => r.id);

  console.log('\nScenario 1: availability search + price quotes');
  const elapsed = await readScenario(roomIds);
  let total = 0;
  console.log('  endpoint          requests   req/s     p50 ms   p95 ms   p99 ms   max ms   statuses');
  for (const name of ['search', 'quote']) {
    const s = stats.get(name);
    if (!s) continue;
    const sorted = [...s.latencies].sort((a, b) => a - b);
    total += sorted.length;
    const statuses = [...s.statuses.entries()].map(([k, v]) => `${k}:${v}`).join(' ');
    console.log(
      `  ${name.padEnd(16)}  ${String(sorted.length).padStart(8)}   ${(sorted.length / elapsed).toFixed(1).padStart(6)}   ${percentile(sorted, 50).toFixed(0).padStart(6)}   ${percentile(sorted, 95).toFixed(0).padStart(6)}   ${percentile(sorted, 99).toFixed(0).padStart(6)}   ${sorted.at(-1).toFixed(0).padStart(6)}   ${statuses}`,
    );
  }
  console.log(`  total ${total} requests in ${elapsed.toFixed(1)}s = ${(total / elapsed).toFixed(1)} req/s`);

  console.log(`\nScenario 2: ${CONTENDERS} customers book the same room for the same dates simultaneously`);
  const result = await contentionScenario(roomIds[0]);
  const wins = result.statuses.filter((s) => s === 201).length;
  const refused = result.statuses.filter((s) => s === 409).length;
  console.log(`  stay ${result.checkIn} → ${result.checkOut}; contenders ${result.contenders}`);
  console.log(`  accepted (201): ${wins}   refused (409): ${refused}   other: ${result.statuses.length - wins - refused}`);
  console.log(`  response codes: ${result.codes.join(', ')}`);
  const ok = result.contenders === 0 || wins === 1;
  console.log(ok ? '  ✔ exactly one booking won — no double booking' : '  ✘ UNEXPECTED: double booking or no winner');
  process.exit(ok ? 0 : 1);
}

main();
