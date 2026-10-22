#!/usr/bin/env node
// Booking-consistency audit against a RUNNING stack (Node 18+, no dependencies).
//
//   node scripts/consistency-audit.mjs [--base http://localhost:8080] [--rounds 5]
//
// Exercises the inventory model through the public API only and checks the outcome after every scenario:
//   1. 10 customers race for the same room/dates                → exactly one booking accepted
//   2. one request sent 5x concurrently with one Idempotency-Key → one booking, same id every time
//   3. back-to-back stays on one room ([d, d+2) and [d+2, d+4))  → both accepted
//   4. cancellation racing a new booking for the same dates     → never two active bookings
//   5. payment failure after a hold                              → booking PAYMENT_FAILED, room free again
//   6. 5 concurrent payment confirmations (same + different keys)→ one charge, booking confirmed once
//
// Scenarios that need fault injection (hold expiry followed by a late payment, a room-service timeout,
// duplicate signed webhooks) are covered by automated tests — see docs/testing/TESTING.md.
// Registers ~20 throw-away customers: the gateway allows 10 credential requests/min/IP by default, so run
// with GV_AUTH_RATE_LIMIT raised (see docs) or expect the script to wait between registrations.

const args = Object.fromEntries(
  process.argv.slice(2).reduce((acc, a, i, all) => (a.startsWith('--') ? [...acc, [a.slice(2), all[i + 1]]] : acc), []),
);
const API = (args.base ?? 'http://localhost:8080') + '/api/v1';
const ROUNDS = Number(args.rounds ?? 5);
let failures = 0;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const key = (p) => `${p}-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
function check(label, ok, detail = '') {
  if (!ok) failures++;
  console.log(`  ${ok ? '✔' : '✘'} ${label}${detail ? ` — ${detail}` : ''}`);
}

async function call(method, path, { token, body, idem } = {}) {
  const headers = { Accept: 'application/json', 'X-Requested-With': 'XMLHttpRequest' };
  if (body) headers['Content-Type'] = 'application/json';
  if (token) headers.Authorization = `Bearer ${token}`;
  if (idem) headers['Idempotency-Key'] = idem;
  const res = await fetch(API + path, { method, headers, body: body ? JSON.stringify(body) : undefined });
  const text = await res.text();
  return { status: res.status, body: text ? JSON.parse(text) : null };
}

async function customer(i) {
  for (let attempt = 0; attempt < 8; attempt++) {
    const r = await call('POST', '/auth/register', {
      body: { email: `audit-${Date.now()}-${i}-${Math.random().toString(36).slice(2, 7)}@example.test`, password: 'correct-horse-42', fullName: `Audit ${i}` },
    });
    if (r.status === 201) return r.body.accessToken;
    if (r.status === 429) {
      await sleep((r.body?.retryAfterSeconds ?? 6) * 1000);
      continue;
    }
    throw new Error(`registration failed: ${r.status} ${JSON.stringify(r.body)}`);
  }
  throw new Error('registration kept being rate limited');
}

function hotelDate(offsetDays) {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Kolkata' }).format(new Date(Date.now() + offsetDays * 86_400_000));
}
const bookingBody = (roomId, checkIn, checkOut, name = 'Audit Guest') => ({
  roomId, checkIn, checkOut, guests: 1, primaryGuest: { fullName: name, email: 'audit@example.test' },
});
async function available(roomId, checkIn, checkOut) {
  return (await call('GET', `/rooms/${roomId}/availability?checkIn=${checkIn}&checkOut=${checkOut}`)).body.available;
}
async function waitForStatus(token, id, wanted, ms = 30_000) {
  const end = Date.now() + ms;
  let s;
  while (Date.now() < end) {
    s = (await call('GET', `/bookings/${id}/status`, { token })).body?.status;
    if (wanted.includes(s)) return s;
    await sleep(700);
  }
  return s;
}

async function main() {
  console.log(`GrandVista booking-consistency audit against ${API}\n`);
  const rooms = (await call('GET', '/rooms?size=50&sort=PRICE_ASC')).body.content;
  const room = rooms[rooms.length - 1].id; // a high-priced room the demo rarely uses
  // stays must start within the hotel's 365-day booking window (base + 64 ≤ 364)
  const base = 120 + Math.floor(Math.random() * 180);
  const d = (n) => hotelDate(base + n);

  console.log('1. Ten customers race for the same room and dates');
  const tokens = await Promise.all(Array.from({ length: 10 }, (_, i) => customer(i)));
  const race = await Promise.all(tokens.map((t, i) => call('POST', '/bookings', { token: t, idem: key(`race${i}`), body: bookingBody(room, d(0), d(2)) })));
  const winners = race.filter((r) => r.status === 201);
  check('exactly one accepted', winners.length === 1, `201×${winners.length}, 409×${race.filter((r) => r.status === 409).length}, other×${race.filter((r) => ![201, 409].includes(r.status)).length}`);
  const losers = race.filter((r) => r.status === 409);
  check('the nine others told ROOM_UNAVAILABLE', losers.length === 9 && losers.every((r) => r.body.code === 'ROOM_UNAVAILABLE'), `${losers.length} refusal(s)`);
  check('room no longer available for those dates', (await available(room, d(0), d(2))) === false);

  console.log('\n2. One request sent five times concurrently with the same Idempotency-Key');
  const idem = key('same');
  const t2 = tokens[1];
  const repeats = await Promise.all(Array.from({ length: 5 }, () => call('POST', '/bookings', { token: t2, idem, body: bookingBody(room, d(10), d(11)) })));
  const ids = new Set(repeats.filter((r) => r.status < 300).map((r) => r.body.id));
  check('every response refers to the same single booking', ids.size === 1 && repeats.every((r) => r.status < 300), `statuses ${repeats.map((r) => r.status).join(',')}`);
  const mine = (await call('GET', '/bookings/my?scope=ALL&size=100', { token: t2 })).body.content.filter((b) => b.checkIn === d(10));
  check('customer history contains it once', mine.length === 1, `${mine.length} booking(s)`);

  console.log('\n3. Back-to-back stays on the same room');
  const a = await call('POST', '/bookings', { token: tokens[2], idem: key('b2b-a'), body: bookingBody(room, d(20), d(22)) });
  const b = await call('POST', '/bookings', { token: tokens[3], idem: key('b2b-b'), body: bookingBody(room, d(22), d(24)) });
  check('both accepted (check-out day is free for the next guest)', a.status === 201 && b.status === 201, `${a.status}, ${b.status}`);
  const overlap = await call('POST', '/bookings', { token: tokens[4], idem: key('b2b-c'), body: bookingBody(room, d(21), d(23)) });
  check('an overlapping third stay is refused', overlap.status === 409, String(overlap.status));

  console.log(`\n4. Cancellation racing a new booking for the same dates (${ROUNDS} rounds)`);
  for (let round = 0; round < ROUNDS; round++) {
    const ci = d(30 + round * 3);
    const co = d(31 + round * 3);
    const holder = tokens[5];
    const challenger = tokens[6];
    const held = await call('POST', '/bookings', { token: holder, idem: key('hold'), body: bookingBody(room, ci, co) });
    const [cancel, attempt] = await Promise.all([
      call('POST', `/bookings/${held.body.id}/cancel`, { token: holder, body: {} }),
      call('POST', '/bookings', { token: challenger, idem: key('chal'), body: bookingBody(room, ci, co) }),
    ]);
    const holderStatus = (await call('GET', `/bookings/${held.body.id}/status`, { token: holder })).body.status;
    const bothActive = attempt.status === 201 && !['CANCELLED', 'EXPIRED', 'PAYMENT_FAILED', 'REJECTED'].includes(holderStatus);
    check(`round ${round + 1}: never two active bookings`, !bothActive,
      `cancel ${cancel.status} → ${holderStatus}; challenger ${attempt.status}${attempt.body?.code ? ' ' + attempt.body.code : ''}`);
    if (attempt.status === 201) await call('POST', `/bookings/${attempt.body.id}/cancel`, { token: challenger, body: {} });
  }

  console.log('\n5. Payment failure after the hold');
  const t5 = tokens[7];
  const f = await call('POST', '/bookings', { token: t5, idem: key('fail'), body: bookingBody(room, d(50), d(52)) });
  check('room held while awaiting payment', (await available(room, d(50), d(52))) === false);
  const declined = await call('POST', `/payments/${f.body.paymentId}/confirm`, { token: t5, idem: key('pay'), body: { paymentMethodToken: 'sim_card_declined' } });
  check('payment declined', declined.body.status === 'FAILED', declined.body.status);
  check('booking becomes PAYMENT_FAILED', (await waitForStatus(t5, f.body.id, ['PAYMENT_FAILED'])) === 'PAYMENT_FAILED');
  check('room available again', (await available(room, d(50), d(52))) === true);

  console.log('\n6. Five concurrent payment confirmations for one booking');
  const t6 = tokens[8];
  const p = await call('POST', '/bookings', { token: t6, idem: key('pay6'), body: bookingBody(room, d(60), d(61)) });
  const sameKey = key('dup');
  const confirms = await Promise.all([
    ...Array.from({ length: 3 }, () => call('POST', `/payments/${p.body.paymentId}/confirm`, { token: t6, idem: sameKey, body: { paymentMethodToken: 'sim_card_success' } })),
    ...Array.from({ length: 2 }, (_, i) => call('POST', `/payments/${p.body.paymentId}/confirm`, { token: t6, idem: key(`other${i}`), body: { paymentMethodToken: 'sim_card_success' } })),
  ]);
  const detail = (await call('GET', `/payments/${p.body.paymentId}`, { token: t6 })).body ?? {};
  const charges = (detail.events ?? []).filter((e) => e.eventType === 'AUTHORIZATION_STARTED').length;
  check('payment authorised exactly once', charges === 1 && detail.payment?.status === 'SUCCEEDED', `starts=${charges}, status=${detail.payment?.status}, responses=${confirms.map((c) => c.status).join(',')}`);
  check('booking confirmed', (await waitForStatus(t6, p.body.id, ['CONFIRMED'])) === 'CONFIRMED');
  const history = ((await call('GET', `/bookings/${p.body.id}/history`, { token: t6 })).body ?? []).filter((h) => h.toStatus === 'CONFIRMED');
  check('confirmed exactly once in the audit trail', history.length === 1, `${history.length}`);

  // tidy up the live bookings this audit created
  for (const [t, id] of [[tokens[2], a.body.id], [tokens[3], b.body.id], [t2, [...ids][0]], [t6, p.body.id], [tokens[winners.length ? race.indexOf(winners[0]) : 0], winners[0]?.body.id]]) {
    if (id) await call('POST', `/bookings/${id}/cancel`, { token: t, body: {} });
  }

  console.log(`\n${failures === 0 ? 'AUDIT PASSED' : `AUDIT FAILED (${failures} check(s))`}`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
