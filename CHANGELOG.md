# Changelog

All notable changes to this project. Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Each entry lists only what was actually changed and verified.

## [Unreleased]

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
