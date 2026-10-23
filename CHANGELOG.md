# Changelog

All notable changes to this project. Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Each entry lists only what was actually changed and verified.

## [Unreleased]

### Container counts corrected (23 Oct 2026)
- **Fixed:** "23 containers" (and "25 with observability", "8", "11") replaced by measured numbers. Counted with
  `docker compose ps -q | wc -l`: **19** containers for the default stack, **21** with `--profile observability`;
  10 of them are Spring Boot instances; Eureka lists 7 applications / 10 instances UP; the 19 containers use
  2 694 MiB (`docker stats`). Updated README, TEAM_OVERVIEW (md + pdf), traceability matrix, report, checklist and
  DEPLOYMENT.md.

### Multi-property portfolio and stock reconciliation (23 Oct 2026)
- **Added (room-service):** `Property` entity (code, name, city, country, time zone, active) with migration V3; every
  room type, room and pricing policy belongs to a property; room numbers and type codes unique per property; 3 demo
  hotels (Hyderabad 11 rooms, Goa 4, Dubai 4); search by `propertyId` or `city`; stay dates validated in the hotel's
  time zone; per-hotel pricing policies; `/properties` (public) and `/admin/properties` (ADMIN writes); every admin
  view filterable by `propertyId`; holds return the hotel.
- **Added (booking, notification):** bookings store `property_id`/`property_name` (booking V3); booking events and
  e-mails carry the hotel (notification V2); `/admin/bookings?propertyId`.
- **Added (stock reconciliation):** `ReconciliationEngine` + `InventoryReconciliationService` compare each hotel's
  allocations with the booking-service's bookings (new SERVICE-only `/internal/v1/bookings/lookup` and
  `/holding-inventory`, called with a new `room-service` client credential `ROOM_SERVICE_CLIENT_SECRET`); flags orphan
  holds, allocations without booking, status mismatches, confirmed bookings without allocation and pending bookings
  without hold; releases expired orphan holds; audit events `RECONCILIATION_RUN/FINDING/RELEASED`; runs stored
  (room V4); scheduled job (default 10 min) and `/admin/inventory/reconciliation[/run|/runs]` (STAFF, ADMIN).
- **Added (frontend):** destination selector (all, city, hotel) on home and search; hotel on room cards, room page,
  checkout, confirmation and bookings; hotel list on the home page; admin hotel selector; Reconciliation page.
- **Changed:** report team table (names and roles, no register numbers), report §3/§4/§7/§13, new §16.1–16.2,
  future work; README; API reference; traceability matrix §0 (PS016 lines); TEAM_OVERVIEW.
- **Verified:** `mvn -B clean verify` 214 backend tests, 0 failures; coverage 88.6 % lines (4 385 / 4 951), 67.7 %
  branches (1 125 / 1 661); frontend 34 tests, lint, type check, build; e2e 70/70 on the rebuilt stack.
- **Limitations:** all hotels price in INR; staff are not restricted per hotel; the booking-service cancellation
  calendar uses one configured zone.

### Public release and team overview (23 Oct 2026)
- **Security:** full-history scan before publishing found 10 expired JWT access tokens of throwaway k6 test
  customers in `docs/testing/evidence/k6-summary.json` (local issuer, CUSTOMER role, expired 22 Oct 16:22 UTC). Removed
  from every commit (only `setup_data.tokens`; k6 metrics kept), force-pushed while still private, and the k6
  script now never writes tokens. Re-scan: no JWTs, `.env` values, keys or tokens in any commit.
- **Changed:** repository made **public**; README, badge (passing) and architecture image verified for a signed-out
  visitor.
- **Changed:** report title page — student name, Team Members table (members 2–3 to be completed), Review Schedule
  (Review 1, Review 2, Final Review; dates to be completed) replacing the submission date.
- **Added:** `docs/TEAM_OVERVIEW.md` and `docs/TEAM_OVERVIEW.pdf` (3 pages) for teammates.

### CI on GitHub (23 Oct 2026)
- **Changed:** repository published privately at `maheswar-padi/grandvista-hospitality`; commit author set to
  PADI SESHA MAHESWARA RAO (GitHub no-reply e-mail) for this repository and rewritten on the unpublished history;
  `.claude-transfer/` workspace archives (Maven cache and a source tarball, no credentials inside) removed from the
  history and git-ignored — GitHub had rejected the first push because of their size.
- **Added:** CI status badge in the README.
- **Verified:** first GitHub Actions run [39832912584](https://github.com/maheswar-padi/grandvista-hospitality/actions/runs/39832912584)
  green in ~15 min: backend 196 tests, 0 failures, coverage 85.9 % lines / 65.9 % branches; frontend 29 tests,
  lint, type check, build; all images built; full stack on a clean runner with e2e 56/56 and DB isolation 40/40.
- **Still pending:** Razorpay test-API run (skipped at the owner's request), cloud deployment.

### Phase 8 — Report, demo and final polish
- **Added:** k6 load test (`load/k6/load-test.js`, `scripts/load-test-k6.sh`, which raises and restores the
  gateways' rate limits); architecture and event-flow diagrams as Mermaid source + PNG (`docs/diagrams/`);
  `scripts/start.sh` (one-command start).
- **Changed:** academic report (architecture, DB per service, events, HA, payments, CI/CD, measured results,
  numbered screenshot placeholders, limitations and future work), README (overview, diagram, quick start, URLs,
  demo sign-ins, troubleshooting, limitations), 7–10 minute demo script with a screenshot capture guide,
  traceability matrix, testing and architecture documents.
- **Verified (final run, 22 Oct 2026):** backend 196 tests, 0 failures; coverage 86.4 % lines / 65.8 % branches;
  frontend 29 tests + lint + type check + build; e2e 56/56; k6: 0 % errors, search p95 135.9 ms, quote p95 60.8 ms,
  contention 1 accepted / 9 refused.

### Phase 7 — Cloud deployment preparation (no deployment performed)
- **Added:** `docker-compose.prod.yml` (Caddy with automatic HTTPS as the only published service, `prod` profile,
  CORS and e-mail links on `https://GV_DOMAIN`), `infrastructure/docker/caddy/Caddyfile`, `scripts/deploy.sh`,
  `.env` keys `GV_DOMAIN` and `ACME_EMAIL`; step-by-step Oracle Cloud Always Free guide in DEPLOYMENT.md.
- **Changed:** the `prod` profile no longer hard-disables demo data; it follows `GV_DEMO_SEED_ENABLED` (default off).
- **Verified (locally, GV_DOMAIN=localhost):** only 80/443 published, HTTP→HTTPS redirect, HSTS, smoke test over
  HTTPS 52/52 after making Caddy answer 404 for `/internal/*`. **Not done:** a real cloud deployment (needs your
  cloud account and a domain).

### Phase 6 — CI workflow (prepared; not yet run on GitHub)
- **Changed:** `.github/workflows/ci.yml` — backend build + all tests with JaCoCo (coverage summary in the job
  summary; surefire and coverage reports uploaded as artifacts), frontend lint/type check/tests/build, then an `e2e`
  job that builds all images, starts the full HA stack, runs `e2e-smoke.sh` and `verify-db-isolation.sh`, and
  uploads container logs. Maven and npm caches; steps run with `bash -eo pipefail`; any failing test fails the run.
- **Pending at the time:** a GitHub repository — done on 23 Oct 2026 (see "CI on GitHub").

### Phase 5 — Security and quality hardening
- **Reviewed (already in place, re-verified by tests):** RS256 JWT at the gateway and in every service, roles
  CUSTOMER/STAFF/ADMIN (+ SERVICE), 15-min access tokens, rotating refresh tokens with reuse detection, RFC 9457
  (successor of RFC 7807) problem responses, CORS allow-list, per-IP rate limits, no stack traces, correlation IDs
  through gateway, services and RabbitMQ.
- **Fixed:** `PUT /api/v1/admin/users/{id}/status` with an empty body disabled the account (primitive `boolean`
  defaulted to false); the field is now required (400 `VALIDATION_FAILED`).
- **Added:** JaCoCo per module and an aggregate `coverage` module, `scripts/coverage-summary.sh`; new tests for
  catalogue administration and the demo seeder (room), cancellation edge cases — unpaid cancel, payment in
  progress, failed/pending refunds, admin retry, hotel-initiated full refund (booking), the gateway error handler
  (10 cases) and account status validation (auth); axe checks for the payment page in both provider modes.
- **Added:** optional `observability` Compose profile — Prometheus 3.5 (scrapes every instance via Docker DNS) and
  Grafana 12.1 with a provisioned "GrandVista — service overview" dashboard.
- **Verified:** backend 196 tests, 0 failures, coverage 86.6 % lines / 66.0 % branches (aggregate); frontend 29
  tests, lint, type check, build; payment page at 375 px without overflow (live); Prometheus 6/6 targets up.

### Phase 4 — High availability and resilience
- **Added:** two Eureka servers replicating to each other (`EUREKA_PEER_URL`; clients list both); two API gateways
  behind a new nginx load balancer `gateway-lb` (public port 8080; the frontend's nginx also balances both);
  `X-Gateway-Instance` response header; two booking-service replicas.
- **Added:** Resilience4j. Gateway: circuit breaker per route with a `503 SERVICE_UNAVAILABLE` + `Retry-After`
  fallback, and a retry that re-sends a request to another instance only when the connection failed. Booking
  service: circuit breaker + retry (transient failures only) for room/payment calls, replacing the hand-written
  `Retrier` (removed from platform-commons with its 4 tests).
- **Added:** `scripts/failover-demo.sh` and its recorded output.
- **Fixed (found by the failover demo):** a request to a killed booking replica failed with 503 because
  `NoRouteToHostException` was not retried; requests to a crashed instance cost 2 s each until Eureka evicted it.
  Now: connect-failure retry covers it, gateway connect timeout 1 s, leases 5 s/15 s, Eureka eviction every 5 s
  without the read-only response cache, load-balancer cache 5 s.
- **Verified:** backend 177 tests, 0 failures; e2e 56/56; failover run A 99/100 requests + 4/4 bookings (one request
  in flight to the killed gateway got 504), run B 100/100 + 4/4.

### Phase 3 — Payment gateways (simulated + Razorpay test mode)
- **Added:** Razorpay test-mode gateway behind the existing `PaymentProvider` interface, selected with
  `GV_PAYMENT_PROVIDER` (`simulated` default, `razorpay`). New `POST /api/v1/payments/{id}/checkout` creates the
  Razorpay order once per payment (migration `V2__provider_order.sql`); `confirm` accepts the Checkout result,
  verifies the signature, looks the payment up at Razorpay (order/amount/currency) and captures it; signed,
  de-duplicated webhooks at `/api/v1/payments/webhooks/razorpay`; reconciliation by order; refunds in paise;
  automatic refund of a capture that arrives for a voided or failed payment. Keys only from `.env`; live keys
  and missing keys stop the service at start-up (verified live).
- **Added:** frontend Razorpay Checkout flow (`lib/razorpay.ts`, payment page) and a Content-Security-Policy that
  allows Razorpay's hosts only in razorpay mode (nginx template, verified for both modes).
- **Fixed (found by the new tests):** the payment-service security rules only allowed the simulated webhook path,
  so Razorpay webhooks were rejected before signature verification.
- **Verified:** payment-service 52 tests (Razorpay: 8 against a fake Razorpay API + 3 signature tests with
  openssl-computed vectors); frontend 27 tests (3 new for both payment modes); e2e 56/56 in simulated mode.
  **Not verified:** calls to Razorpay's real test API (needs test keys).

### Phase 2 — Message broker and e-mail notifications
- **Added:** RabbitMQ 4.1 (management UI, loopback only) and Mailpit 1.27 (local SMTP inbox) to Compose.
  Topic exchange `grandvista.events`; events `BOOKING_CREATED`, `BOOKING_CONFIRMED`, `BOOKING_CANCELLED`,
  `HOLD_EXPIRED` (booking-service outbox, new migration `V2__booking_outbox.sql`) and `PAYMENT_SUCCEEDED`,
  `PAYMENT_FAILED` (payment-service outbox, now published to RabbitMQ; `GV_EVENT_TRANSPORT=http` keeps the old
  direct HTTP delivery). Publisher confirms + mandatory routing; per-consumer durable queues with dead-letter
  queues and consumer retries with exponential backoff.
- **Added:** `notification-service` with its own PostgreSQL server: consumes all events, keeps a booking-contact
  read model, sends Thymeleaf HTML + text e-mails (booking confirmed, payment receipt, cancellation, payment failed,
  hold expired), never twice for the same event and type; staff API `GET /api/v1/admin/notifications`.
- **Changed:** the booking-service consumes payment events from RabbitMQ (the internal HTTP endpoint remains);
  payment events carry a new `provider` field; event contracts documented in `contracts/README.md`.
- **Changed:** JVM memory settings (heap 60% of a 448 MiB limit, metaspace/code-cache caps, `MALLOC_ARENA_MAX=2`,
  heap shrinking) — Java containers dropped from 310–410 MiB to 225–345 MiB each (measured with `docker stats`).
- **Found by tests:** duplicate payment events processed concurrently by two consumers can call the room
  confirm twice (harmless: it is idempotent; the booking still transitions once and publishes one event) —
  documented and asserted. The booking-side event record now explicitly ignores unknown fields.
- **Verified:** backend 165 tests, 0 failures (new: real-broker tests in payment, booking and notification;
  Mailpit-backed e-mail assertions); e2e 56/56 including 4 e-mail checks; all queues and DLQs empty afterwards;
  DB isolation 40/40 (five databases).

### Phase 1 — Database per service
- **Changed:** the single PostgreSQL server is replaced by four servers (`auth-db`, `room-db`, `booking-db`,
  `payment-db`), each with its own volume, health check and credentials; services wait for their own database
  with `depends_on: condition: service_healthy`. Each service connects with a non-superuser owner role that
  exists only on its own server. Host ports (loopback): 5433 auth, 5434 room, 5435 booking, 5436 payment.
- **Added:** `scripts/verify-db-isolation.sh`; demo customer and staff accounts (`DemoUserSeeder`, passwords
  generated into `.env`, disabled in the `prod` profile); `init-env.sh` now adds missing keys to an existing `.env`
  without changing existing values.
- **Fixed:** `e2e-smoke.sh` failed right after a fresh start (`SERVICE_UNAVAILABLE` while Eureka propagated
  instances); it now waits until every service is routable through the gateway.
- **Verified:** fresh volumes → 11 containers healthy → e2e 52/52; isolation check 28/28; backend 149 tests,
  0 failures.

## [1.0.0] — 2026-10-22 — Baseline

First commit of the existing platform (`chore: initial project baseline`).

### Included
- Spring Boot 3.5 / Spring Cloud 2025.0 microservices: discovery-server (Eureka), api-gateway, auth-service,
  room-service, booking-service, payment-service (simulated provider), shared platform-commons library.
- React 19 + TypeScript web application (customer journey and administration dashboard).
- Docker Compose stack (one PostgreSQL server with four service-owned databases), CI workflow (not yet run on
  GitHub), documentation set and academic report draft.

### Verified at baseline (22 Oct 2026, local machine)
- `mvn -B -ntp clean verify`: 148 tests, 0 failures, 0 errors, 0 skipped.
- Frontend: lint, type check, 24 tests, production build — all passing.
- `docker compose up -d --build --wait`: 8 containers healthy; `./scripts/e2e-smoke.sh`: 52 passed, 0 failed.

### Known gaps at baseline
- Simulated payment provider only; no cloud deployment; CI never run on GitHub; single Eureka and gateway
  instance; one PostgreSQL server for four databases; no e-mail notifications or message broker.
