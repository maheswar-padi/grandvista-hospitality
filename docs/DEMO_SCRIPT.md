# Demonstration script

A 20–25 minute live demonstration covering the sixteen points required by the project brief. Every step has
been run on the local stack; the automated equivalent of steps 3–16 is `./scripts/e2e-smoke.sh` (52 checks).

## Before the demonstration (once)

```bash
./scripts/init-env.sh
```
```bash
docker compose up -d --build
```
```bash
./scripts/e2e-smoke.sh
```
The smoke test proves the stack works and leaves some bookings so the dashboard has data. Keep three browser
tabs ready: the web app (http://localhost:3000), Swagger (http://localhost:8080/swagger-ui.html) and the Eureka
dashboard (http://localhost:8761 — credentials `EUREKA_USERNAME`/`EUREKA_PASSWORD` in `.env`). Read the admin
credentials from `.env` (`GV_BOOTSTRAP_ADMIN_EMAIL`, `GV_BOOTSTRAP_ADMIN_PASSWORD`) beforehand; do not display the
file on screen.

Shell helpers for the curl steps:
```bash
API=http://localhost:8080/api/v1
```

---

### 1. Application start-up (2 min)
```bash
docker compose ps
```
Show eight healthy containers and explain the start order (postgres → discovery → auth → room/payment → booking →
gateway → frontend) enforced by health checks. Show one service's log line with the correlation ID format:
```bash
docker compose logs --tail=5 booking-service
```

### 2. Service registration (1 min)
Eureka dashboard: AUTH-SERVICE, ROOM-SERVICE, BOOKING-SERVICE, PAYMENT-SERVICE, API-GATEWAY all **UP**.
Optional — load balancing live:
```bash
docker compose up -d --scale room-service=2
```
After ~40 s two ROOM-SERVICE instances appear; requests are shared between them (see
[testing evidence](testing/evidence/load-balancing.txt)). Scale back with `--scale room-service=1`.

### 3. Room search (1 min)
Web app → enter dates two weeks ahead, 2 guests → **Search availability**. Point out filters, sort, total price for
the stay, and that images are labelled illustrations.

### 4. Availability checking (1 min)
Open a room → the panel says "Available …" and lists booked nights for the next 60 days. API view:
```bash
curl -s "$API/rooms?size=1" | jq -r '.content[0].id'
```
```bash
curl -s "$API/rooms/<ROOM_ID>/availability?checkIn=2027-03-10&checkOut=2027-03-12"
```

### 5. Dynamic pricing (2 min)
Room details → change dates to include a Friday/Saturday: the breakdown shows `weekend ×1.15`; dates between 20 Dec
and 5 Jan show `Festive peak ×1.3`. Explain the formula, HALF_UP rounding, fee and tax, and the policy version.
Admin → **Pricing** shows the active policy and history.

### 6. Customer registration and login (1 min)
**Create account** with a new e-mail (password ≥ 10 characters with a letter and a digit). Show the invalid-form
messages first. Explain: access token in memory, refresh token in an HttpOnly cookie (DevTools → Application →
Cookies: `gv_refresh` is HttpOnly, SameSite=Strict); reload the page — still signed in.

### 7. Booking creation (2 min)
**Reserve this room** → checkout (pre-filled) → **Reserve**. The booking reference appears and the room is held for
15 minutes (countdown). Mention the Idempotency-Key: double-clicking or retrying cannot create a second booking.
In another browser profile, open the same room for the same dates → the panel says **Not available for these dates** and the reserve button is disabled. (If two people reserve at the same moment, the loser sees "Sorry, this room was just booked" — see step 15 for the API view.)

### 8. Payment simulation (1 min)
Show the "Simulated payment — no real money is charged" banner and the test methods. Choose **Simulated bank
redirect – approved by webhook** → **Pay**.

### 9. Booking confirmation (1 min)
The confirmation page first shows **Waiting for payment confirmation** and only switches to **Your stay is
confirmed** once the booking-service has confirmed the room (signed webhook → payment SUCCEEDED → outbox event →
room confirmed). Point out the reference, dates and price snapshot.

### 10. Inventory update (1 min)
Search the same dates again → the room is no longer listed. Admin → **Rooms & inventory** / overview: occupancy
and the allocation exist.

### 11. Customer booking history (1 min)
**My bookings** → Upcoming / Past / All tabs; open the booking → timeline (Processing → Awaiting payment →
Confirmed) and payment status "Paid · simulated".

### 12. Booking cancellation (1 min)
**Cancel booking** → the dialog shows the policy (100% ≥ 7 days, 50% 2–6 days, none within 48 h) and the refund
amount → confirm → status **Cancelled**, refund shown, payment **Refunded**.

### 13. Inventory release (30 s)
Search the dates again → the room is available.

### 14. Administrator room management (3 min)
Sign out → sign in as the administrator → **Administration**:
* Overview: occupancy chart (next 30 nights), bookings trend, payments — all from live data; "Show data as a table".
* Rooms & inventory → **Status** → set a room to *Maintenance* (reason "Leak") → it disappears from customer search;
  set it back to *Active*.
* **Edit** a room → change the base rate → save; upload a JPEG/PNG photo; try renaming a `.svg` to `.png` → refused.
* Users & roles → grant STAFF to the demo customer.

### 15. Error handling for an unavailable room (1 min)
```bash
TOKEN=$(curl -s -X POST $API/auth/register -H 'Content-Type: application/json' -d "{\"email\":\"demo-$RANDOM@example.test\",\"password\":\"correct-horse-42\",\"fullName\":\"Demo\"}" | jq -r .accessToken)
```
Book the room that is already held/confirmed for the same dates:
```bash
curl -s -X POST $API/bookings -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -H "Idempotency-Key: demo-$RANDOM$RANDOM" -d '{"roomId":"<ROOM_ID>","checkIn":"<SAME_CHECKIN>","checkOut":"<SAME_CHECKOUT>","guests":1,"primaryGuest":{"fullName":"Demo","email":"demo@example.test"}}' | jq
```
→ `409` with `"code": "ROOM_UNAVAILABLE"` and "You have not been charged". Also show invalid dates (`400
INVALID_DATE_RANGE`) and a customer calling an admin API (`403 ACCESS_DENIED`).

### 16. Failed payment handling (1 min)
Book again (new dates) → pay with **Simulated card – declined** → the confirmation page shows **Payment was not
successful**, nothing charged; search shows the room available again.

### Optional extras
* Concurrency: `node scripts/load-test.mjs --vus 5 --duration 10 --contenders 8` (raise `GV_AUTH_RATE_LIMIT` first;
  see [TESTING.md](testing/TESTING.md)) → "exactly one booking won".
* Consistency audit: `node scripts/consistency-audit.mjs` (same note).
* Trace one request: send `-H 'X-Correlation-Id: demo-trace-0001'` with a booking and run
  `docker compose logs --no-log-prefix | grep demo-trace-0001`.

## Troubleshooting during the demo

| Problem | Fix |
|---|---|
| 503 just after start-up | services still registering — wait 30 s |
| 429 `RATE_LIMITED` when registering many users | wait a minute (10 credential requests/min/IP) |
| Hold countdown expired | start a new booking; the old one becomes *Expired* automatically |
| Session lost after reload | use `http://localhost`, not an IP address (Secure cookie) |
