# Changelog

All notable changes to this project. Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Each entry lists only what was actually changed and verified.

## [Unreleased]

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
