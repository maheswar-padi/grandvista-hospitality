# GrandVista Hospitality — Inventory Control & Dynamic Room Reservation Platform

> University project **PS016** · SOA Programming and Microservices (**24SDCS03R**) · K L University
>
> GrandVista is a **fictional** hotel. Payments use a **simulated** provider (or Razorpay **test mode**) — no real
> money is ever moved.

[![CI](https://github.com/maheswar-padi/grandvista-hospitality/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/maheswar-padi/grandvista-hospitality/actions/workflows/ci.yml)

A microservices hotel-booking platform: customers search live availability, see a transparent dynamic price,
reserve a room (which is **held** for 15 minutes), pay, and receive an authoritative confirmation and e-mails.
Hotel staff manage inventory, room status, pricing policy, bookings and payments from an administration dashboard.

The interesting part is the **distributed booking workflow** — a saga across independently deployed services with
idempotent steps, compensations, transactional outboxes, RabbitMQ events and a database-level guarantee that a room
can never be double-booked — running on a highly available setup (2 Eureka servers, 2 gateways, 2 booking replicas)
that keeps working when an instance is killed.

---

## Contents

1. [Quick start](#quick-start-one-command) · 2. [Features](#features) · 3. [Architecture](#architecture) ·
4. [URLs and ports](#urls-and-ports) · 5. [Accounts](#accounts) · 6. [Tests](#tests) ·
7. [Technology](#technology) · 8. [Repository layout](#repository-layout) · 9. [Local development](#local-development) ·
10. [Configuration](#configuration) · 11. [Troubleshooting](#troubleshooting) · 12. [Documentation](#documentation) ·
13. [Limitations and future work](#limitations-and-future-work)

## Quick start (one command)

**Prerequisites:** Docker Desktop / Docker Engine with Compose v2 and **about 4 GB of memory for Docker** (the
stack uses ≈ 3 GB), `openssl`, `curl` and `jq` (for the test scripts).

```bash
./scripts/start.sh
```

It creates `.env` (random secrets, never printed, git-ignored) and the JWT key pair if they are missing, builds
and starts all 23 containers, waits until every one is healthy, and prints the URLs below. The first build takes
several minutes. Then check the whole platform end to end (56 checks, about a minute):

```bash
./scripts/e2e-smoke.sh
```

Open **http://localhost:3000**. Other useful commands:

| Task | Command |
|---|---|
| Also start Prometheus + Grafana | `./scripts/start.sh --observability` |
| Follow one service's logs | `docker compose logs -f --tail=100 booking-service` |
| Failover demonstration | `./scripts/failover-demo.sh` |
| k6 load test | `./scripts/load-test-k6.sh` |
| Stop (keeps data) | `docker compose down` |
| Delete **all** data (asks you to type `DELETE`) | `./scripts/reset-data.sh` |

## Features

| Area | What is implemented |
|---|---|
| **Rooms & inventory** | Rooms, room types, amenities, photographs (validated uploads), operational status (active / maintenance / out of service / retired), search & filters, availability for `[checkIn, checkOut)`, calendar of booked nights, admin inventory views and audit trail |
| **Dynamic pricing** | Deterministic engine: base × weekend × season × demand (own occupancy), clamped to min/max, per-night breakdown, service fee and tax, `BigDecimal` HALF_UP rounding, versioned immutable policies, price snapshot stored with every booking |
| **Bookings** | Idempotent creation (`Idempotency-Key`), temporary inventory hold, authoritative price check, payment initiation, saga confirmation via payment events, cancellation policy (100% / 50% / 0%), auditable status history, recovery job for stuck/expired bookings |
| **Events & e-mail** | RabbitMQ topic exchange with transactional outboxes in booking and payment services, publisher confirms, per-consumer queues with dead-letter queues and retries; notification-service sends HTML e-mails (confirmation, payment receipt, cancellation, failed payment, expired hold), suppressing duplicates when an event is redelivered; captured locally by Mailpit |
| **Payments** | Explicit state machine, provider interface with two gateways — simulated (default) and Razorpay test mode (server-created orders, Checkout, signature + server-side verification, capture, signed webhooks, refunds, automatic refund of late captures) — idempotent charges, HMAC-signed timestamped webhooks with de-duplication, refunds (partial/full), reconciliation of timed-out charges, transactional outbox to the booking service |
| **Security** | RS256 JWT (JWKS), rotating refresh tokens in an HttpOnly SameSite=Strict cookie with reuse detection, BCrypt, account lockout, gateway rate limiting, CORS allow-list, roles (CUSTOMER / STAFF / ADMIN / SERVICE), ownership checks returning 404, service-to-service client credentials, no public admin creation |
| **Platform** | Two Spring Cloud Gateways behind nginx, two peered Eureka servers, client-side load balancing, Resilience4j circuit breakers/retries, correlation IDs end-to-end, RFC 9457 problem responses, health/readiness probes, Prometheus metrics, Flyway migrations, one PostgreSQL server per service, Docker Compose (23 containers), optional Prometheus + Grafana, GitHub Actions CI |
| **Web app** | React + TypeScript: home, search, room details with live quote, register/sign-in, checkout, simulated payment, live confirmation, customer dashboard, booking details & cancellation, admin dashboard with charts, rooms editor, pricing, bookings, payments, users |

## Architecture

![GrandVista architecture](docs/diagrams/architecture.png)

*Source: [docs/diagrams/architecture.mmd](docs/diagrams/architecture.mmd). Event flow:
[docs/diagrams/event-flow.png](docs/diagrams/event-flow.png).*

* **Edge:** the web app's nginx and the `gateway-lb` nginx (port 8080) balance across **two API gateways**
  (JWT check, CORS, rate limits, Resilience4j circuit breaker + fallback per route, retry on connection failure).
* **Discovery:** **two Eureka servers** replicate to each other; every client knows both.
* **Services:** auth, room (inventory + dynamic pricing), booking (**two replicas**, saga orchestrator), payment
  (simulated / Razorpay test mode), notification (e-mails). Each owns **its own PostgreSQL server**
  (`./scripts/verify-db-isolation.sh`).
* **Events:** booking and payment events go from transactional outboxes to **RabbitMQ** (`grandvista.events`);
  consumers have retry + dead-letter queues and ignore duplicates. E-mails are captured by **Mailpit**.

Details (sequence diagrams, state machines, failure handling, deployment): [docs/architecture/ARCHITECTURE.md](docs/architecture/ARCHITECTURE.md).

## URLs and ports

| What | URL | Notes |
|---|---|---|
| Web application | http://localhost:3000 | nginx serving the SPA and proxying `/api` |
| API (load balancer → 2 gateways) | http://localhost:8080/api/v1 | the only public API entry point; `X-Gateway-Instance` shows which gateway answered |
| Swagger UI (all services) | http://localhost:8080/swagger-ui.html | development only (`API_DOCS_ENABLED`) |
| Eureka dashboards (2 peers) | http://localhost:8761 · http://localhost:8762 | loopback only, HTTP Basic (`EUREKA_USERNAME`/`EUREKA_PASSWORD` from `.env`) |
| PostgreSQL (one server per service) | auth 5433 · room 5434 · booking 5435 · payment 5436 · notification 5437 | loopback only, per-service credentials in `.env` |
| RabbitMQ management UI | http://localhost:15672 | loopback only, `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` from `.env` |
| Mailpit (captured e-mails) | http://localhost:8025 | loopback only; every e-mail the platform sends lands here |
| Prometheus · Grafana (optional) | http://localhost:9090 · http://localhost:3001 | `docker compose --profile observability up -d`; Grafana user `admin`, password `GRAFANA_ADMIN_PASSWORD` in `.env` |
| Services (internal network only) | auth 8081 · room 8082 · booking 8083 · payment 8084 · notification 8085 | not published to the host |

## Accounts

* **Customers** register themselves in the web app (every self-registration is a `CUSTOMER`).
* **Administrator:** created once at start-up from `GV_BOOTSTRAP_ADMIN_EMAIL` / `GV_BOOTSTRAP_ADMIN_PASSWORD`
  (generated into `.env` by `init-env.sh` — read it there; it is never logged). It is only created when no
  administrator exists. There is no API or UI that lets an anonymous user create an administrator.
* **Demo accounts** (created when `GV_DEMO_SEED_ENABLED=true`): customer `guest@grandvista.local` and staff
  `staff@grandvista.local`; their passwords are generated into `.env` (`GV_DEMO_CUSTOMER_PASSWORD`,
  `GV_DEMO_STAFF_PASSWORD`). The `prod` profile creates them only if `GV_DEMO_SEED_ENABLED=true`.
* **Staff:** an administrator grants the `STAFF` role under *Administration → Users & roles*.
* **Demo data:** 11 fictional rooms across 5 types are seeded on first start when `GV_DEMO_SEED_ENABLED=true`.
  Room images are labelled illustrations, not photographs.
* **Test payment methods** (simulated): `sim_card_success`, `sim_card_declined`, `sim_card_insufficient_funds`,
  `sim_card_async_success`, `sim_card_async_decline`, `sim_card_timeout_success`.

## Tests

| Suite | Command | Last verified result (22 Oct 2026) |
|---|---|---|
| Backend unit + integration + contract (Testcontainers: PostgreSQL, RabbitMQ, Mailpit) | `mvn -B clean verify` | **196 tests**, 0 failures, 0 errors, 0 skipped |
| Code coverage (JaCoCo, all modules) | `./scripts/coverage-summary.sh` after the build | **86.4 % lines**, 65.8 % branches |
| Frontend unit + component + accessibility | `npm --prefix frontend test` | **29 tests**, 0 failures (lint, type check and build pass) |
| End-to-end on the running stack | `./scripts/e2e-smoke.sh` | **56/56** checks (incl. 4 e-mails in Mailpit) |
| Failover (kill gateway, Eureka, booking replica) | `./scripts/failover-demo.sh` | run A 99/100 requests + 4/4 bookings; run B 100/100 + 4/4 |
| Load (k6, 20 virtual users) + contention | `./scripts/load-test-k6.sh` | search p95 135.9 ms, quote p95 60.8 ms, 0 % errors; 10 parallel bookings of one room → **1 accepted, 9 refused** |
| Database isolation | `./scripts/verify-db-isolation.sh` | 40/40 |
| **GitHub Actions CI** (all of the above except failover/load, on a clean runner) | push to `main` | **green** — [run 39832912584](https://github.com/maheswar-padi/grandvista-hospitality/actions/runs/39832912584): backend 196 tests, coverage 85.9 % lines; frontend 29 tests; full stack + e2e 56/56; DB isolation 40/40 |

Details and raw outputs: [docs/testing/TESTING.md](docs/testing/TESTING.md), [docs/testing/evidence/](docs/testing/evidence/).

## Technology

Java 21 · Spring Boot 3.5.16 · Spring Cloud 2025.0.3 (Gateway server-webflux, Netflix Eureka, LoadBalancer,
CircuitBreaker) · Resilience4j 2.2 · Spring Security / OAuth2 Resource Server · Spring Data JPA (Hibernate 6) ·
Flyway · PostgreSQL 16 · RabbitMQ 4.1 (Spring AMQP) · Thymeleaf + Mailpit · springdoc-openapi 2.8 ·
Micrometer/Prometheus/Grafana · JUnit 5, Mockito, Testcontainers, JaCoCo, k6 · React 19 · TypeScript 5.9 · Vite 7 ·
React Router 7 · TanStack Query 5 · React Hook Form + Zod 4 · Tailwind CSS 4 · Recharts 3 · Vitest + Testing
Library + axe-core · nginx · Caddy · Docker Compose · GitHub Actions.

Why these choices: [docs/architecture/ARCHITECTURE.md §11](docs/architecture/ARCHITECTURE.md#11-technology-decisions).

## Repository layout

```
grandvista-hospitality/
├── frontend/                    React + TypeScript SPA (Vite); nginx template with provider-aware CSP
├── services/
│   ├── discovery-server/        Eureka registry (runs as 2 peers)
│   ├── api-gateway/             Spring Cloud Gateway (runs as 2): routes, JWT, CORS, rate limits, circuit breakers
│   ├── auth-service/            identity, JWT (RS256 + JWKS), refresh tokens, roles, demo accounts
│   ├── room-service/            rooms, availability, holds/allocations, dynamic pricing, media
│   ├── booking-service/         reservations + saga, cancellation, recovery, events (runs as 2)
│   ├── payment-service/         payments: simulated + Razorpay test mode, webhooks, refunds, outbox
│   └── notification-service/    RabbitMQ consumer, Thymeleaf e-mails, sent-mail log
├── shared/platform-commons/     correlation IDs, problem errors, JWT wiring, service clients, messaging helpers
├── coverage/                    aggregated JaCoCo report
├── contracts/                   REST and event message contracts verified by provider and consumer tests
├── infrastructure/docker/       postgres init, rabbitmq, gateway-lb (nginx), caddy, prometheus, grafana
├── load/k6/                     k6 load test
├── scripts/                     start, init-env, e2e-smoke, failover-demo, verify-db-isolation, load tests, deploy …
├── docs/                        architecture, API, database, testing (+ evidence), deployment, security, report, diagrams
├── .github/workflows/ci.yml     CI: tests + coverage, frontend, images, full stack + smoke test
├── docker-compose.yml           local / demo stack
├── docker-compose.prod.yml      production overlay (Caddy HTTPS, nothing else published)
└── .env.example                 every setting, with dummy values
```

## Local development

Run the infrastructure in Docker and one service from your IDE or Maven (example: room-service, whose database
is published on `localhost:5434`):

```bash
docker compose up -d room-db rabbitmq discovery-server-1 discovery-server-2 auth-service
```
```bash
mvn -pl services/room-service -am spring-boot:run
```

Export the values from `.env` first (`set -a; . ./.env; set +a`) and map them as described in
[DEPLOYMENT.md](docs/deployment/DEPLOYMENT.md#running-services-locally). Frontend with hot reload (proxies `/api`
to :8080):

```bash
npm --prefix frontend install && npm --prefix frontend run dev
```

## Configuration

All configuration is environment-based; see [.env.example](.env.example) for every variable and its meaning.
Secrets are never committed. Profiles: default (development: demo data and Swagger enabled) and `prod`
(disables Swagger and demo data, refuses ephemeral JWT keys and missing webhook secrets).
Payment provider: `GV_PAYMENT_PROVIDER=simulated` (default) or `razorpay` with **test-mode** keys — see
[DEPLOYMENT.md → Razorpay test mode](docs/deployment/DEPLOYMENT.md#razorpay-test-mode).

## Troubleshooting

| Symptom | Check |
|---|---|
| `docker compose up` says a variable is missing | run `./scripts/init-env.sh` (it only adds missing keys) |
| Containers restart or are killed | Docker needs ~4 GB of memory (`docker stats`); stop other projects' containers |
| A service keeps restarting after regenerating `.env` | database passwords changed → `./scripts/reset-data.sh` |
| 503 `SERVICE_UNAVAILABLE` right after start-up | services still registering in Eureka — wait ~30 s (`e2e-smoke.sh` waits automatically) |
| 503 with `Retry-After: 10` | the gateway's circuit breaker for that service is open — check the service's logs |
| No e-mail in Mailpit | `docker compose logs notification-service`; RabbitMQ UI → `notification.events.dlq` |
| Login works but the session is lost on reload | use `http://localhost` (not an IP) or HTTPS — the refresh cookie is `Secure` |
| 429 `RATE_LIMITED` during experiments | per-IP limits per gateway (`GV_API_RATE_LIMIT`, `GV_AUTH_RATE_LIMIT`) |
| Payment-service exits with "razorpay but these settings are empty" | add the Razorpay test keys or set `GV_PAYMENT_PROVIDER=simulated` |

More (database, Eureka, routing, events, stuck holds, payments, migrations):
[docs/deployment/OPERATIONS.md](docs/deployment/OPERATIONS.md).

## Documentation

| Document | Contents |
|---|---|
| [Architecture](docs/architecture/ARCHITECTURE.md) | service boundaries, data ownership, saga, events, payments, consistency, HA, decisions |
| [API reference](docs/api/API_REFERENCE.md) | every endpoint and event, roles, gateway routes, examples, errors |
| [Event and REST contracts](contracts/README.md) | message examples verified from both sides |
| [Database](docs/database/DATABASE.md) | ER diagrams, table ownership, constraints, indexes, migrations |
| [Testing](docs/testing/TESTING.md) | strategy, commands, verified results, coverage, failover, load ([raw evidence](docs/testing/evidence/)) |
| [Deployment](docs/deployment/DEPLOYMENT.md) · [Operations](docs/deployment/OPERATIONS.md) | Docker, configuration, Razorpay test mode, cloud (Oracle Free VM), observability, diagnosis |
| [Security](docs/security/SECURITY.md) | threat model, controls, token strategy, residual risks |
| [Demonstration script](docs/DEMO_SCRIPT.md) | 7–10 minute walkthrough and screenshot guide |
| [Requirement traceability](docs/TRACEABILITY_MATRIX.md) | every requirement → code, tests, evidence, status |
| [Project report](docs/project-report/PROJECT_REPORT.md) | academic report for PS016 |
| [Changelog](CHANGELOG.md) | what changed in each phase and how it was verified |

## Limitations and future work

**Still simulated or not done:**

* **Payments:** the simulated provider is the default. The **Razorpay test-mode** gateway is implemented and tested
  against a fake Razorpay API, but has **not** been run against Razorpay's real test API (no test keys were
  available). Live keys are refused; no real money or card data is handled.
* **Cloud:** **not deployed.** The production overlay (Caddy HTTPS) and deploy script were tested on the
  development machine only.
* **E-mail:** captured by a local Mailpit inbox; no real e-mail provider.
* **Availability:** Eureka, gateways and booking-service run twice; auth, room, payment, notification, RabbitMQ and
  the databases run once (memory of the demonstration laptop). Rate limits are per gateway instance.
* **Evidence limits:** performance figures come from one laptop; responsive checks used browser emulation.

**Future work:** Razorpay test-API run and e2e in Razorpay mode · cloud deployment with continuous deployment from CI · real e-mail/SMS provider · shared (Redis) rate limiting · replicas for the remaining services, RabbitMQ
quorum queues and PostgreSQL replication · OpenTelemetry tracing · room-type inventory and demand forecasting.

Full status per requirement: [docs/TRACEABILITY_MATRIX.md](docs/TRACEABILITY_MATRIX.md).
