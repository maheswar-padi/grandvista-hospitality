# Demonstration script (7–10 minutes)

Booking → payment → e-mail → failover → CI. Every step below has been run on the local stack; timings are
approximate. A longer, feature-by-feature walkthrough follows in the appendix.

## Before the demonstration (once, ~5 minutes)

```bash
./scripts/init-env.sh
```
```bash
docker compose up -d --build --wait
```
```bash
./scripts/e2e-smoke.sh
```

The smoke test (70 checks) proves the stack works and leaves bookings and e-mails for the dashboards. Open these
tabs in advance (credentials are in `.env`; never show the file on screen):

| Tab | URL | Sign-in |
|---|---|---|
| Web app | http://localhost:3000 | demo customer `guest@grandvista.local` (`GV_DEMO_CUSTOMER_PASSWORD`) |
| Mailpit | http://localhost:8025 | — |
| Eureka 1 · Eureka 2 | http://localhost:8761 · http://localhost:8762 | `EUREKA_USERNAME` / `EUREKA_PASSWORD` |
| RabbitMQ | http://localhost:15672 | `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` |
| GitHub Actions | your repository's *Actions* tab | only after CI has been pushed |

Optional: `docker compose --profile observability up -d` and Grafana at http://localhost:3001 (`admin` /
`GRAFANA_ADMIN_PASSWORD`).

## The walkthrough

### 1. Architecture in one picture (1 min)
Show `docs/diagrams/architecture.png`: two Eureka servers, two gateways behind nginx, five services with five
databases, RabbitMQ, Mailpit. Then `docker compose ps` — 23 healthy containers.

### 2. Booking (2 min)
Web app → search dates two weeks ahead, 2 guests → open a room: the price breakdown shows weekend/season
multipliers, fee and tax (dynamic pricing). Sign in as the demo customer → **Reserve this room** → the room is
**held for 15 minutes** (countdown). Mention the Idempotency-Key: a double click cannot create two bookings.

### 3. Payment (1 min)
On the payment page: *Simulated payment — no real money*. Choose **Simulated Visa – approved** → **Pay**. The
confirmation page switches to **Your stay is confirmed** only after the booking-service has received
`payment.succeeded` from RabbitMQ and confirmed the room.
*Razorpay test mode* (only if test keys were configured, see DEPLOYMENT.md): the same button opens Razorpay
Checkout; the server verifies the signature and asks Razorpay before confirming.

### 4. E-mail (1 min)
Mailpit tab: **Your stay is confirmed — GV-…** and **Payment receipt — GV-…** arrived within seconds. Open one to
show the HTML template. RabbitMQ tab → *Queues*: `booking.payment-events` and `notification.events` with 2
consumers each, dead-letter queues empty. Mention: a redelivered event never sends a second e-mail.

### 5. Failover (3 min)
```bash
./scripts/failover-demo.sh
```
While it runs, narrate: it kills a gateway (traffic moves to the other one — watch the
`X-Gateway-Instance` split), then a Eureka server (the peer keeps the registry; a restarted service registers
with Eureka 2), then a booking replica (the gateway retries on the other replica; RabbitMQ re-delivers its
messages). Each step makes a complete booking. End on the summary table. Show both Eureka dashboards afterwards.

### 6. Quality and CI (1 min)
Show the coverage summary (`./scripts/coverage-summary.sh` after `mvn -B clean verify`: 88.6 % lines locally, 87.9 % on CI) and the
GitHub Actions run with the CI badge in the README (latest verified run: 39907254564, all jobs green).

### Questions to expect
* *Why can't two people book the same room?* PostgreSQL exclusion constraint + per-room row lock (k6: 10 parallel
  attempts → 1 accepted, 9 refused).
* *What if RabbitMQ is down?* Events wait in each service's outbox and are published when it returns; bookings
  awaiting payment are also reconciled by the recovery job.
* *What if the payment provider times out?* The payment stays PENDING and the reconciliation job asks the provider.

## Screenshot capture guide (for the report)

The numbers match the `[Screenshot n: …]` placeholders in `docs/project-report/PROJECT_REPORT.md` §25. Use a
desktop browser window about 1440 px wide unless stated.

| # | Where | What must be visible |
|---|---|---|
| 1 | http://localhost:3000 | home page with the search bar (dates, guests) |
| 2 | search results after choosing dates | room cards with the **total stay price** and filters |
| 3 | a room's details page | photo, amenities and the itemised quote (per night, fee, tax, total) |
| 4 | payment page of a new booking | "Simulated payment" notice, hold countdown, list of test methods |
| 5 | confirmation page after paying | "Your stay is confirmed", booking reference, dates, price |
| 6 | http://localhost:8025 | the confirmation e-mail opened (HTML view) and the receipt in the list |
| 7 | *My bookings* → open the booking | timeline Processing → Awaiting payment → Confirmed, payment "Paid · simulated" |
| 8 | sign in as admin → *Administration* | overview with occupancy and booking charts |
| 9 | http://localhost:8761 and :8762 | "Instances currently registered": API-GATEWAY (2), BOOKING-SERVICE (2), all UP |
| 10 | http://localhost:15672 → *Queues and Streams* | both queues with 2 consumers; `.dlq` queues at 0 messages |
| 11 | terminal after `./scripts/failover-demo.sh` | the "Summary" table and the final PASSED line |
| 12 | http://localhost:3001 → Dashboards → GrandVista | request rate, latency, heap and e-mail panels with data |
| 13 | http://localhost:8080/swagger-ui.html | Swagger UI with the service selector |
| 14 | GitHub → *Actions* and the README | a green run (backend, frontend, e2e) and the CI badge — after CI has run |

## Troubleshooting during the demo

| Problem | Fix |
|---|---|
| 503 right after start-up | services still registering — wait 30 s (the smoke test waits automatically) |
| 429 `RATE_LIMITED` when registering many users | wait a minute (10 credential requests/min/IP per gateway) |
| Hold countdown expired | start a new booking; the old one becomes *Expired* and a "hold expired" e-mail is sent |
| No e-mail in Mailpit | `docker compose logs notification-service`; RabbitMQ UI → `notification.events.dlq` |
| Failover demo shows a failure | read the step's log lines; a request in flight to a killed gateway can fail once (documented in TESTING.md) |
| Session lost after reload | use `http://localhost`, not an IP address (Secure cookie) |

## Appendix — feature-by-feature checks (longer demo)

| Feature | How to show it |
|---|---|
| Availability | room page lists booked nights; `GET /api/v1/rooms/{id}/availability?checkIn&checkOut` |
| Cancellation | *Cancel booking* → policy (100 % ≥ 7 days, 50 % 2–6 days, none within 48 h) → refund → cancellation e-mail |
| Failed payment | pay with **Simulated card – declined** → room released, "payment not completed" e-mail |
| Admin | set a room to *Maintenance* (disappears from search), edit a price, upload a JPEG/PNG (SVG refused), grant STAFF |
| Errors | book an already-held room → `409 ROOM_UNAVAILABLE`; invalid dates → `400 INVALID_DATE_RANGE`; customer on admin API → `403` |
| Tracing | send `-H 'X-Correlation-Id: demo-trace-0001'` and run `docker compose logs --no-log-prefix \| grep demo-trace-0001` |
| Load | `./scripts/load-test-k6.sh` (raises and restores the rate limits) |
