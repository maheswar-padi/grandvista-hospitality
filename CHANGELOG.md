# Changelog

All notable changes to this project. Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Each entry lists only what was actually changed and verified.

## [Unreleased]

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
