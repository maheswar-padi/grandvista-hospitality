# Implementation checklist

`[x]` = done **and** verified by the evidence named · `[~]` = implemented, not fully verified · `[ ]` = not done.
Last updated 23 October 2026. Full results: [docs/testing/TESTING.md](testing/TESTING.md).

## Backend (verified: `mvn -B clean verify` — 214 tests, 0 failures, 0 errors, 0 skipped; Testcontainers PostgreSQL 16, RabbitMQ, Mailpit)
- [x] platform-commons (correlation IDs, RFC 9457 errors, JWT wiring, service clients, retry, Specs) — 6 tests
- [x] discovery-server (Eureka, HTTP Basic) — 2 tests
- [x] api-gateway (routes, JWT pre-check, CORS, rate limit, size limit, problem errors, circuit breakers) — 23 tests
- [x] auth-service (RS256/JWKS, rotating refresh cookie + reuse detection, lockout, roles, service tokens) — 19 tests
- [x] room-service (hotels, search, availability, pricing engine, holds, exclusion constraint, scheduled hold sweeper, media, admin, stock reconciliation) — 50 tests
- [x] booking-service (saga, idempotency, cancellation policy, scheduled recovery job, inbox, history, timeouts, reconciliation lookups) — 56 tests
- [x] payment-service (state machine, simulated provider, Razorpay test mode, webhooks incl. concurrent duplicates, refunds, outbox, reconciliation) — 52 tests
- [x] Contracts verified provider + consumer side (`/contracts`)

## Frontend (verified: lint, typecheck, build, Vitest 34 tests incl. axe checks)
- [x] All customer pages and admin dashboard implemented and integrated with the real API
- [x] Browser-verified on the Docker stack: search → details/quote → register → checkout → async simulated
      payment → live confirmation → dashboard; admin overview + rooms/pricing/bookings/payments/users
- [x] Booking history: upcoming / past / all, empty state, pagination, error + retry, final statuses
      (`account.test.tsx`, `bookingHistoryIsPrivatePaginatedAndShowsFinalStatuses`, live browser check)
- [x] Responsive: every page inspected at 375, 390, 430, 768, 1024, 1440 px (Chromium emulation, not physical
      devices); no horizontal overflow after fixes

## Containers & runtime (verified on Docker 29.9, 4 GB VM)
- [x] 7 images rebuilt (multi-stage, non-root); `docker compose config` valid
- [x] 19 containers healthy (21 with `--profile observability`); 7 applications / 10 instances UP in Eureka (gateway, discovery and booking ×2) — counted 23 Oct 2026
- [x] `scripts/e2e-smoke.sh` — 70/70 checks passed (23 Oct); earlier 52/52 also with two room-service instances
- [x] Load balancing with 2 room-service instances: gateway 30/30, booking→room 4/4 ([evidence](testing/evidence/load-balancing.txt))
- [x] Load test (20 VUs, 30 s, limits temporarily raised then restored to 10/300): 1,119 req/s, p95 search 47 ms /
      quote 32 ms, 0 errors; 10 concurrent bookings → 1 accepted, 9 refused
- [x] Consistency audit `scripts/consistency-audit.mjs` — 18 checks passed ([evidence](testing/evidence/consistency-audit.txt))
- [x] Correlation ID traced across gateway, booking-service and room-service logs
- [x] Hold expiry: unpaid booking `GV-YAVVSA` reached `EXPIRED` and its hold was released. It expired ~4 min late
      because the Docker VM was suspended while the host slept (no log activity, then Eureka re-registration);
      the scheduled sweeper and recovery job are now covered by `HoldExpirySchedulingIntegrationTest` and
      `BookingRecoverySchedulingTest`

## Database per service (Phase 1, 22 Oct 2026)
- [x] Four PostgreSQL servers (auth-db, room-db, booking-db, payment-db), own credentials/volume/health check;
      `scripts/verify-db-isolation.sh` 28/28; fresh volumes → stack healthy → e2e 52/52
- [x] Demo customer and staff accounts seeded from `.env` (`demoAccountsAreSeededWithTheirRolesOnly`)

## Message broker and e-mail (Phase 2, 22 Oct 2026)
- [x] RabbitMQ events with outboxes, publisher confirms, DLQs and idempotent consumers (real-broker tests)
- [x] notification-service + Mailpit; e-mails verified by `NotificationFlowIntegrationTest` and e2e (now 70/70)

## Payments (Phase 3, 22 Oct 2026)
- [x] Gateway selection `GV_PAYMENT_PROVIDER` (simulated default)
- [~] Razorpay test mode: implemented, tested against a fake Razorpay API and mocked Checkout; **not run against Razorpay's test API (needs keys)**

## High availability (Phase 4, 22 Oct 2026)
- [x] 2 Eureka peers, 2 gateways behind nginx (`gateway-lb`), 2 booking replicas, Resilience4j in gateway and booking
- [x] `scripts/failover-demo.sh`: run A 99/100 requests + 4/4 bookings, run B 100/100 + 4/4 ([evidence](testing/evidence/failover-demo.txt))

## Security and quality (Phase 5, 22 Oct 2026)
- [x] Request-DTO validation scan; fixed silent account disabling on empty status body
- [x] JaCoCo: 88.6 % lines / 67.7 % branches aggregate (local, 23 Oct), 214 backend tests; frontend 34 tests incl. axe on payment page
- [x] Optional Prometheus + Grafana profile; payment page re-checked live at 375 px (no overflow)

## Report, demo and polish (Phase 8, 22 Oct 2026)
- [x] k6 load test (evidence/k6-output.txt), diagrams PNG, report/README/demo script/traceability updated
- [x] Report title page: name, Team Members table, Review Schedule (23 Oct 2026)
- [x] Team table: KOONI BHAVYA SREE (Team Lead), CHUNCHU PURVI SATHVIKA, PADI SESHA MAHESWARA RAO (names and roles)
- [ ] Department, guide, academic year, review dates, institutional pages, screenshots (need the team)
- [x] GitHub repository (public) + CI green (latest run 39907254564: 214 + 34 tests, e2e 70/70) + badge
- [ ] Razorpay test keys; cloud account + domain (need the student)

## Multi-property and stock reconciliation (23 Oct 2026)
- [x] Property entity, 3 demo hotels, search/pricing/admin per hotel, bookings store the hotel (`PropertyPortfolioIntegrationTest`, e2e §1b)
- [x] Reconciliation job + admin report per hotel, expired orphan holds released, audit trail (`ReconciliationEngineTest`, `InventoryReconciliationIntegrationTest`, e2e §11)
- [x] Backend 214 tests, coverage 88.6 % lines / 67.7 % branches; frontend 34 tests

## Defects found and fixed
- Earlier phase: CHAR vs VARCHAR schema mismatches · text-block SQL concatenation (room search) · GiST
  exclusion-constraint deadlock (per-room row lock) · LocalDate JSON as arrays in load-balanced clients ·
  Spring Data `CheckIn` keyword parsing · Eureka UP/DOWN health loop · null-parameter JPQL filters returning 500
- This phase: orphan-payment refund used booking fields that can be null (now uses the event's payment id and
  amount) · header overflow at 768 px for administrators (nav breakpoint moved to `lg`) · admin grid widened by
  its nav scroller · admin tables 706 px wide on a 375 px phone (screen-reader-only header escaping an
  unpositioned scroll container) · dialog not scrollable on short screens · 20 px back-link tap targets ·
  consistency-audit script fixes (dates outside the booking window, a vacuous check, a crash)

## Documentation
- [x] README.md · docs/architecture/ARCHITECTURE.md · contracts/README.md · CI workflow
- [x] docs/api/API_REFERENCE.md · docs/database/DATABASE.md · docs/testing/TESTING.md (+ `evidence/`)
- [x] docs/deployment/DEPLOYMENT.md · docs/deployment/OPERATIONS.md · docs/security/SECURITY.md
- [x] docs/DEMO_SCRIPT.md · docs/TRACEABILITY_MATRIX.md · docs/project-report/PROJECT_REPORT.md

## Not done / limitations
- Razorpay not run against the real test API (needs keys) · cloud deployment (none performed) ·
  in-memory gateway rate limiting (per instance) · single instances of auth/room/payment/notification, RabbitMQ, DBs ·
  e-mails captured by Mailpit only (no real provider) · responsive checks by emulation only
