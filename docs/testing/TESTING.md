# Testing strategy and verified results

Every result in this document was produced by actually running the command shown, on 21–22 October 2026, on
macOS (Apple Silicon, 8 GB RAM) with Java 21 (Temurin), Maven 3.9, Node 22 and Docker 29.9 (4 GB VM).
Raw outputs of the live runs are kept in [`evidence/`](evidence). Nothing here is estimated.

## 1. Test layers

| Layer | What it proves | Tools | Where |
|---|---|---|---|
| Unit | pricing formula and rounding, stay/overlap rules, booking & payment state machines, cancellation policy, webhook signatures, outbox backoff, retry policy, correlation IDs, client-credential checks, lockout | JUnit 5, AssertJ, parameterised tests | `*Test.java` in `domain`, `pricing`, `provider`, `client`, `service` packages |
| Controller + security | validation, status codes, problem format, role and ownership rules, CSRF header, internal-API isolation | MockMvc with `spring-security-test` JWTs | `*IntegrationTest.java` |
| Integration (real database) | Flyway migrations, `ddl-auto=validate`, exclusion constraint under concurrency, locking, idempotency keys, specifications, scheduled jobs | **Testcontainers PostgreSQL 16, RabbitMQ 4.1, Mailpit 1.27** | `AuthFlowIntegrationTest`, `RoomInventoryIntegrationTest`, `CatalogueAdminIntegrationTest`, `HoldExpirySchedulingIntegrationTest`, `BookingSagaIntegrationTest`, `BookingRecoverySchedulingTest`, `BookingMessagingIntegrationTest`, `PaymentFlowIntegrationTest`, `RazorpayFlowIntegrationTest` (fake Razorpay API), `RabbitOutboxPublisherIntegrationTest`, `NotificationFlowIntegrationTest` |
| Gateway | routing, JWT validation (RS256 via JWKS), CORS, rate limiting, size limits, hidden internal paths | real gateway + reactor-netty stub upstream | `GatewayIntegrationTest` |
| Contract | messages exchanged by services keep their fields; clients parse them; timeouts are bounded | shared JSON in `/contracts`, MockRestServiceServer, real slow HTTP server | `DownstreamContractTest`, `OutboxRelayContractTest`, provider checks in the integration tests |
| Frontend | API client, validation schemas, redirect safety, key components and flows, authoritative confirmation, booking history states, accessibility (axe-core) | Vitest, Testing Library, jsdom, axe-core | `frontend/src/**/*.test.ts(x)` |
| End-to-end | every user journey through the real gateway, all services, real PostgreSQL | bash + curl + jq | `scripts/e2e-smoke.sh` |
| Consistency audit | the inventory model under races, retries, cancellations and payment failures, live | Node script | `scripts/consistency-audit.mjs` |
| Load | throughput/latency of read traffic and booking contention | Node script | `scripts/load-test.mjs` |
| Browser & responsive | real pages in a browser at six viewport widths | in-app browser (Chromium) + DOM audit | §6 |

## 2. Commands

```bash
mvn verify
```
All backend tests (needs Docker for the Testcontainers tests; they are skipped, not failed, when Docker is absent).

```bash
npm --prefix frontend test && npm --prefix frontend run lint && npm --prefix frontend run typecheck && npm --prefix frontend run build
```

```bash
./scripts/e2e-smoke.sh
```
Against a running stack (`docker compose up -d`).

```bash
node scripts/consistency-audit.mjs --rounds 5
```
Registers ~10 customers: start the gateway with `GV_AUTH_RATE_LIMIT=1000` first (see §5) and restore it afterwards.

```bash
node scripts/load-test.mjs --vus 20 --duration 30 --contenders 10
```
Start the gateway with `GV_API_RATE_LIMIT=1000000 GV_AUTH_RATE_LIMIT=1000` for a throughput run.

## 3. Backend results — `mvn -B clean verify` (23 Oct 2026, after the multi-property and reconciliation work): **214 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS**

The same 214 tests passed on GitHub Actions (run 39907254564). The baseline before Phases 1–5 was 148 tests.
New classes in this round are marked *(new)*. Per-module totals:
platform-commons 4, discovery 2, gateway 23, auth 19, room 50, booking 56, payment 52, notification 8.

### Code coverage (JaCoCo)

Aggregate report over all modules (`coverage/target/site/jacoco-aggregate/index.html`, summary by
`./scripts/coverage-summary.sh`): **88.6 % of lines (4,385 of 4,951), 67.7 % of branches (1,125 of 1,661)** locally;
CI run 39907254564 measured 87.9 % (4,354 of 4,951) / 67.7 % (1,124 of 1,661) — timing-dependent paths vary slightly
between runs. Per module (own tests only): gateway 93.2 %, auth 90.7 %, booking 85.9 %, room 92.3 %, payment 85.6 %,
notification 94.3 %, discovery 83.3 % lines. Coverage measures the automated test suite only; the live e2e,
failover and load tests are not included in it.

| Module | Test class | Tests |
|---|---|---|
| platform-commons | CorrelationIdTest | 2 |
|  | EventTopologyTest | 2 |
| discovery-server | DiscoveryServerApplicationTests | 2 |
| api-gateway | GatewayErrorHandlerTest | 10 |
|  | GatewayIntegrationTest | 13 |
| auth-service | AuthFlowIntegrationTest | 13 |
|  | ServiceClientAuthenticatorTest | 3 |
|  | UserAccountTest | 3 |
| room-service | CatalogueAdminIntegrationTest | 3 |
|  | HoldExpirySchedulingIntegrationTest | 3 |
|  | InventoryReconciliationIntegrationTest *(new)* | 3 |
|  | MediaStorageTest | 2 |
|  | PricingEngineTest | 8 |
|  | PricingPolicyValidationTest | 2 |
|  | PropertyPortfolioIntegrationTest *(new)* | 4 |
|  | ReconciliationEngineTest *(new)* | 9 |
|  | RoomInventoryIntegrationTest | 14 |
|  | StayDatesTest | 2 |
| booking-service | BookingMessagingIntegrationTest | 3 |
|  | BookingRecoverySchedulingTest | 1 |
|  | BookingRulesTest | 24 |
|  | BookingSagaIntegrationTest | 21 |
|  | DownstreamContractTest | 7 |
| payment-service | OutboxRelayContractTest | 3 |
|  | PaymentFlowIntegrationTest | 15 |
|  | PaymentStatusTest | 18 |
|  | RabbitOutboxPublisherIntegrationTest | 3 |
|  | RazorpayFlowIntegrationTest | 8 |
|  | RazorpaySignaturesTest | 3 |
|  | WebhookSignatureTest | 2 |
| notification-service | EmailComposerTest | 2 |
|  | EventContractTest | 2 |
|  | NotificationFlowIntegrationTest | 4 |
| **Total** | | **214** |

### Scenario coverage (selected)

| Scenario | Automated test |
|---|---|
| 8 concurrent overlapping holds → exactly 1 succeeds | `RoomInventoryIntegrationTest.concurrentOverlappingHoldsAllowExactlyOneWinner` |
| back-to-back stays both allowed; overlaps refused | `…nonOverlappingAndBackToBackStaysCanBothBeHeld` |
| hold idempotent per booking and keeps its price | `…holdIsIdempotentPerBookingAndKeepsOriginalPrice` |
| **scheduled** sweeper expires lapsed holds; room bookable again | `HoldExpirySchedulingIntegrationTest.scheduledSweeperExpiresLapsedHoldAndTheRoomBecomesBookableAgain` |
| hold left behind by a crash expires from DB state alone (restart safety) | `…holdLeftBehindWhileTheServiceWasDownIsExpiredFromDatabaseStateAlone` |
| 4 concurrent sweepers + scheduler → each hold expired exactly once; re-run is a no-op | `…concurrentSweepersExpireEachHoldExactlyOnce` |
| payment success after hold expiry → re-acquire if free | `RoomInventoryIntegrationTest.expiredHoldStopsBlockingAndIsReacquiredOnConfirmWhenStillFree` |
| … or `INVENTORY_NO_LONGER_AVAILABLE` if taken → full refund | `…expiredHoldTakenBySomeoneElseCannotBeConfirmed`, `BookingSagaIntegrationTest.paymentSucceededButRoomLostIsCompensatedWithFullRefund` |
| unpaid booking past hold expiry → payment voided, room released, `EXPIRED` | `BookingSagaIntegrationTest.unpaidBookingPastHoldExpiryIsExpiredOrConfirmedIfPaymentActuallySucceeded` |
| recovery job runs on its schedule (not invoked by the test) | `BookingRecoverySchedulingTest` |
| recovery waits while a payment is in progress; repeated recovery is idempotent | `BookingSagaIntegrationTest.recoveryWaitsWhilePaymentIsInProgressAndIsIdempotent` |
| late payment success for an `EXPIRED` booking is refunded, never confirmed; duplicate event ignored | `…latePaymentSuccessForAnExpiredBookingIsRefundedNeverConfirmed` |
| late webhook / late customer payment for a voided payment cannot confirm it | `PaymentFlowIntegrationTest.lateSuccessForAVoidedPaymentCannotConfirmIt` |
| 5 concurrent duplicate webhooks → applied once, one outbox event | `…concurrentDuplicateWebhooksAreAppliedOnce` |
| lost provider response resolved by reconciliation | `…lostProviderResponseIsResolvedByReconciliation` |
| room-service that does not answer → bounded retries (3), fails fast (< 2.9 s) | `DownstreamContractTest.roomServiceThatDoesNotAnswerInTimeFailsFastWithBoundedRetries` |
| room-service/payment-service outage during booking → booking kept `CREATED`, retry resumes, recovery expires abandoned ones | `BookingSagaIntegrationTest.outageLeavesBookingCreatedThenRetryResumesAndRecoveryCleansUpAbandonedOnes` |
| price changed between quote and booking → 409, hold released | `…changedPriceIsRejectedAndHoldReleased` |
| same Idempotency-Key replays; different body rejected | `…retriesWithSameKeyReturnTheSameBookingAndDifferentBodyIsRejected` |
| booking history: private, paginated, empty pages, statuses as stored | `…bookingHistoryIsPrivatePaginatedAndShowsFinalStatuses`, `…searchesWorkWithAndWithoutEachOptionalFilter` |
| refresh-token rotation and reuse detection; lockout; role escalation blocked | `AuthFlowIntegrationTest` |
| refunds partial/full, bounded, idempotent | `PaymentFlowIntegrationTest.refundsArePartialOrFullIdempotentAndBounded` |
| image upload rejects disguised files; media keys can't traverse paths | `RoomInventoryIntegrationTest.imageUploadAcceptsRealImagesAndRejectsDisguisedFiles`, `MediaStorageTest` |
| pricing formula, weekend/season/demand, clamping, HALF_UP rounding, determinism | `PricingEngineTest` |
| 3 hotels; search by city and hotel; per-hotel pricing; inactive hotel refuses holds; holds carry the hotel | `PropertyPortfolioIntegrationTest` |
| reconciliation finds all 5 mismatch types, releases only expired orphan holds, logs each finding once, survives a booking-service outage | `ReconciliationEngineTest`, `InventoryReconciliationIntegrationTest` |

## 4. Frontend results — 23 Oct 2026

| Check | Result |
|---|---|
| `npm run lint` (ESLint incl. a rule forbidding `dangerouslySetInnerHTML`) | passed |
| `npm run typecheck` | passed |
| `npm test` (Vitest) | **34 tests, 5 files, all passed** — `lib.test.ts` 9, `components.test.tsx` 11, `account.test.tsx` 4, `payment.test.tsx` 5, `portfolio.test.tsx` 5 |
| `npm run build` | passed (route-level code splitting; charts in a separate lazy chunk) |
| Accessibility | axe-core: no serious/critical violations on price breakdown, room card, sign-in page, confirmation page, payment page, destination search bar, reconciliation page (colour contrast checked separately by design tokens) |

## 5. Live-stack results (Docker Compose)

| Run | Command | Result | Evidence |
|---|---|---|---|
| End-to-end, multi-property build (23 Oct) | `./scripts/e2e-smoke.sh` | **70 passed, 0 failed** (incl. hotel portfolio §1b and a live reconciliation run §11) | [evidence/e2e-smoke.txt](evidence/e2e-smoke.txt) |
| End-to-end with **two room-service instances** (22 Oct) | same | 52 passed, 0 failed | [evidence/load-balancing.txt](evidence/load-balancing.txt) |
| Consistency audit (22 Oct) | `node scripts/consistency-audit.mjs --rounds 5` (gateway auth limit raised to 1000/min for the run, restored afterwards) | **AUDIT PASSED — 18 checks** | [evidence/consistency-audit.txt](evidence/consistency-audit.txt) |
| Load test (21 Oct) | `node scripts/load-test.mjs --vus 20 --duration 30 --contenders 10` (limits raised for the run, restored afterwards) | see below | [evidence/load-test.txt](evidence/load-test.txt) |
| Load balancing (22 Oct) | `docker compose up -d --scale room-service=2` | gateway 30/30, booking→room 4/4 | [evidence/load-balancing.txt](evidence/load-balancing.txt) |

### Consistency audit outcomes

| Scenario | Outcome observed |
|---|---|
| 10 customers book the same room and dates simultaneously | 1 × 201, 9 × 409 `ROOM_UNAVAILABLE`; room unavailable afterwards |
| same request ×5 concurrently with one Idempotency-Key | statuses 201, 200, 200, 200, 200 — all the same booking; history contains it once |
| back-to-back stays `[d, d+2)` and `[d+2, d+4)` | both 201; an overlapping `[d+1, d+3)` → 409 |
| cancellation racing a new booking (5 rounds) | never two active bookings; challenger refused when it arrived before the release (1 round), accepted after it (4 rounds) |
| payment declined after a hold | payment `FAILED`, booking `PAYMENT_FAILED`, room available again |
| 5 concurrent confirmations (3 same key, 2 different keys) | responses 200, 200, 200, 409, 409; authorisation started exactly once; booking `CONFIRMED` once in its audit trail |

A first run used stay dates more than 365 days ahead by mistake; every request was correctly refused with
`BOOKING_WINDOW_EXCEEDED` and the script was fixed — that run is not counted.

### Load test (measured, single laptop, all services in Docker, limits raised)

| Endpoint | Requests in 30 s | Throughput | p50 | p95 | p99 | max | Errors |
|---|---|---|---|---|---|---|---|
| search (`GET /rooms` with dates) | 16 799 | 559.7 req/s | 17 ms | 47 ms | 85 ms | 374 ms | 0 |
| quote (`GET /rooms/{id}/quote`) | 16 799 | 559.7 req/s | 11 ms | 32 ms | 59 ms | 310 ms | 0 |
| **total** | 33 598 | **1 119.4 req/s** | | | | | 0 |

Contention scenario in the same run: 10 simultaneous bookings for one room → 1 accepted, 9 refused. These numbers
describe this machine only; no claim is made about production capacity.

### Hold expiry observed on the live stack

Booking `GV-YAVVSA` was deliberately left unpaid on 21 Oct. Database state afterwards: booking `EXPIRED`
(`PAYMENT_WINDOW_ELAPSED`, actor `system:recovery`), payment `CANCELLED` (voided), allocation `RELEASED`. Its hold
lapsed at 13:00:55 UTC and recovery ran at 13:04:58: the service logs show **no activity at all** between 12:59 and
13:04:55, when Eureka logged lease re-registration — the Docker VM had been suspended (laptop sleep). Because the
sweeper had not run before recovery released the room, the scheduled sweeper was then verified separately by
`HoldExpirySchedulingIntegrationTest` (§3).

## 6. Browser and responsive verification (22 Oct 2026, in-app Chromium, live stack)

Method: viewport emulation at each width; for every page a DOM audit measured document width vs viewport, elements
past the right edge (outside scroll containers), clipped text, overlapping controls, tap targets < 24 px and table
containment; visual screenshots were taken of representative pages.

| Page | 375 | 390 | 430 | 768 | 1024 | 1440 |
|---|---|---|---|---|---|---|
| Home | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ |
| Room listing (with dates) | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ |
| Room details + quote | ✔¹ | ✔¹ | ✔¹ | ✔¹ | ✔¹ | ✔¹ |
| Sign in / register | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ |
| Checkout | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ |
| Payment | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ |
| Customer dashboard + booking detail | ✔¹ | ✔¹ | ✔¹ | ✔¹ | ✔¹ | ✔¹ |
| Admin: overview, rooms, pricing, bookings, payments, users | ✔² | ✔² | ✔² | ✔² | ✔² | ✔ |

¹ "Back" links were 20 px tall — fixed (padding) after the audit.
² Defects found and fixed, then re-audited at every width: header overflow at 768 px for administrators
(desktop navigation now starts at 1024 px), admin layout grid widened by the horizontal admin menu, and an
`sr-only` table label escaping its scroll container (page rendered 706 px wide on a 375 px phone).

Also verified: mobile menu (expanded state, labels, 36 px targets), date pickers full width on phones, tables
scroll inside their cards, cancel dialog at 375 × 420 (fits, scrolls, Close visible, confirm reachable — before the
fix it could not scroll), full customer journey register → checkout → async simulated payment → live
confirmation → dashboard, session restore from the HttpOnly cookie (cookie not visible to JavaScript).

## Failover demonstration (22 Oct 2026, `scripts/failover-demo.sh`)

Kills one container at a time with SIGKILL while sending 30 public requests through the load balancer and making
one complete booking (create → pay → CONFIRMED); then restarts it. Requests are counted once (no client retries).

| Step | Run A | Run B |
|---|---|---|
| baseline | 30/30 · booking OK | 30/30 · booking OK |
| api-gateway-1 killed | 29/30 · booking OK | 30/30 · booking OK |
| discovery-server-1 killed (+ notification-service re-registers via Eureka 2) | 30/30 · booking OK | 30/30 · booking OK |
| one booking-service replica killed (10 "my bookings" requests) | 10/10 · booking OK | 10/10 · booking OK |

Run A's failure: a request in flight to the killed gateway got 504 from nginx after the connect timeout instead of
being passed to the other gateway. A run before the Phase 4 fixes failed the booking with a replica killed (connect
error `NoRouteToHostException` was not retried). Raw output: [evidence/failover-demo.txt](evidence/failover-demo.txt).

## Production overlay, local HTTPS test (22 Oct 2026)

`docker-compose.prod.yml` with `GV_DOMAIN=localhost` on the development machine (Caddy's local CA, not Let's
Encrypt — no public deployment was made):

| Check | Result |
|---|---|
| Published ports | only Caddy (80, 443); DB, RabbitMQ, Mailpit, Eureka and the old 8080/3000 ports refused connections |
| `http://localhost/` | 308 redirect to HTTPS |
| `https://localhost/` | HTTP/2 200 with `Strict-Transport-Security: max-age=31536000; includeSubDomains` |
| Swagger UI path | served the web app (API docs are disabled by the `prod` profile) |
| `e2e-smoke.sh https://localhost` (e-mail checks skipped) | first run 51/52: `/internal/…` returned the web app (200) instead of 404 — Caddy now answers 404 for `/internal/*`; second run **52/52** |

## k6 load test (22 Oct 2026, `./scripts/load-test-k6.sh`)

k6 1.1.0 in a container on the Docker network, through `gateway-lb` and both gateways; the gateways' per-IP rate
limits were raised for the run and restored afterwards (verified: `GV_API_RATE_LIMIT=300` again).

| Scenario | Result |
|---|---|
| browse — ramp to 20 virtual users, 55 s, search + quote, 0.2 s think time | 7 275 requests in total, 120.9 req/s, **0.00 % failed** |
| search latency | avg 38.1 ms · median 17.6 ms · **p95 135.9 ms** · p99 364.5 ms · max 917.9 ms |
| quote latency | avg 22.3 ms · median 10.6 ms · **p95 60.8 ms** · p99 259.0 ms · max 1 099.3 ms |
| contention — 10 customers book the same room/nights at once | **1 accepted (201), 9 refused (409), 0 unexpected**; contended request avg 2.3 s (serialised on the room lock) |
| thresholds | all passed |

Raw output: [evidence/k6-output.txt](evidence/k6-output.txt), full metrics: [evidence/k6-summary.json](evidence/k6-summary.json).
These numbers include think time and are not a throughput maximum; the earlier Node.js test without think time
measured 1 119 req/s with one gateway.

## 7. Not tested / limitations of the evidence

* No real payment provider (only the simulated one) and no cloud deployment exist to test.
* Physical phones were not used; responsive checks used Chromium device emulation.
* Load figures come from one laptop running every service; they are not capacity claims.
* The Eureka server and the gateway were not tested with multiple instances of themselves.
* Visual regression (pixel comparison) and cross-browser (Firefox/Safari) runs were not performed.
* GitHub Actions workflow has been written but not executed on GitHub (the repository has not been pushed).
