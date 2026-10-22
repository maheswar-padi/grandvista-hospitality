# Deployment guide

Covers local Docker execution (verified), running services from source, configuration, and a documented — **not
performed** — path to a cloud deployment. No cloud resources have been created for this project.

## Prerequisites

| Tool | Version used for verification | Needed for |
|---|---|---|
| Docker Desktop / Engine + Compose v2 | Docker 29.9, Compose 5.6, 4 GB VM memory | the whole stack |
| openssl | system | `scripts/init-env.sh` |
| Java 21 + Maven 3.9 | Temurin 21.0.12, Maven 3.9.16 | building/running services outside Docker |
| Node 22 + npm 10 | 22.20 / 10.9 | frontend development, scripts |
| curl, jq | system | `scripts/e2e-smoke.sh` |

The stack uses about 2.6 GB of memory (seven JVM containers capped at 384–512 MB, PostgreSQL 384 MB, nginx 64 MB).

## Local stack with Docker Compose (verified)

```bash
./scripts/init-env.sh
```
Creates `.env` (mode 600) with random secrets and `infrastructure/secrets/jwt-{private,public}.pem`. Re-running
keeps existing files; `--force` regenerates everything (then reset the database, because its passwords change).

```bash
docker compose up -d --build
```
Builds 7 images (multi-stage; the Maven and npm caches make rebuilds fast) and starts 8 containers. Start order is
enforced by health checks:

```mermaid
flowchart LR
  pg[(postgres)] --> ds[discovery-server] --> au[auth-service]
  au --> ro[room-service] --> bo[booking-service]
  au --> pa[payment-service] --> bo
  bo --> gw[api-gateway] --> fe[frontend]
```

```bash
docker compose ps
```
Every service should show `(healthy)` after about a minute. Then:

```bash
./scripts/e2e-smoke.sh
```

| Task | Command |
|---|---|
| Follow logs of one service | `docker compose logs -f --tail=100 booking-service` |
| All logs since 10 minutes | `docker compose logs --since 10m` |
| Restart one service | `docker compose restart room-service` |
| Rebuild after a code change | `docker compose up -d --build room-service` |
| Run two instances of a service | `docker compose up -d --scale room-service=2` (verified: load is shared 50/50) |
| Stop (keep data) | `docker compose down` |
| Delete all data (asks for confirmation) | `./scripts/reset-data.sh` |

Published ports: 3000 (web app), 8080 (gateway), and on 127.0.0.1 only 8761 (Eureka) and 5433–5436
(the auth, room, booking and payment PostgreSQL servers).
Services and the database are otherwise reachable only on the internal `grandvista` network.

### Volumes

| Volume | Contents | Removed by |
|---|---|---|
| `grandvista-{auth,room,booking,payment}-db-data` | one PostgreSQL server per service | `./scripts/reset-data.sh` only |
| `grandvista-room-media` | uploaded and seeded room images | `./scripts/reset-data.sh` only |

## Configuration

All configuration is environment-based (12-factor). The full list with defaults is [`.env.example`](../../.env.example).

| Variable | Used by | Meaning |
|---|---|---|
| `POSTGRES_ADMIN_PASSWORD`, `AUTH/ROOM/BOOKING/PAYMENT_DB_PASSWORD` | postgres, services | superuser for initialisation; one owner role per service database |
| `EUREKA_USERNAME`, `EUREKA_PASSWORD` | discovery + clients | HTTP Basic for the registry (URL-safe characters) |
| `GV_JWT_PRIVATE_KEY_PATH`, `GV_JWT_PUBLIC_KEY_PATH` | auth-service | RS256 key pair (PEM). Without them, development generates an ephemeral key; `prod` refuses to start |
| `BOOKING_SERVICE_CLIENT_SECRET`, `PAYMENT_SERVICE_CLIENT_SECRET` | auth, booking, payment | client credentials for service tokens (≥ 32 chars) |
| `PAYMENT_WEBHOOK_SECRET` | payment-service | HMAC key for provider callbacks (≥ 32 chars; required in `prod`) |
| `GV_BOOTSTRAP_ADMIN_EMAIL`, `GV_BOOTSTRAP_ADMIN_PASSWORD` | auth-service | first administrator, created only if none exists (password ≥ 12 chars) |
| `GV_HOTEL_ZONE` | room, booking | hotel time zone for stay dates (default `Asia/Kolkata`) |
| `GV_HOLD_TTL` | room-service | how long an unpaid hold lasts (default `15m`) |
| `GV_DEMO_SEED_ENABLED` | room-service | seed 11 fictional rooms into an empty catalogue |
| `GV_COOKIE_SECURE` | auth-service | `Secure` flag on the refresh cookie (keep `true`; browsers accept it on `http://localhost`) |
| `GV_CORS_ALLOWED_ORIGINS` | gateway | browser origins allowed to call the API directly |
| `GV_API_RATE_LIMIT`, `GV_AUTH_RATE_LIMIT` | gateway | requests per minute per client IP |
| `API_DOCS_ENABLED` | all | Swagger/OpenAPI (disable outside development) |
| `SPRING_PROFILES_ACTIVE` | all | `prod` disables docs and demo data and enforces secrets |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | all | set to `ecs` for JSON logs |

## Running services locally

Useful for debugging one service in an IDE while the rest runs in Docker. Example for the room-service:

```bash
docker compose up -d postgres discovery-server auth-service
```
```bash
set -a; . ./.env; set +a
```
```bash
DB_PASSWORD=$ROOM_DB_PASSWORD EUREKA_URL=http://$EUREKA_USERNAME:$EUREKA_PASSWORD@localhost:8761/eureka GV_JWKS_URI=http://localhost:8081/oauth2/jwks mvn -pl services/room-service -am spring-boot:run
```

Each service's `application.yml` defaults to `localhost` URLs: database `localhost:<5433|5434|5435|5436>/grandvista_<svc>` (auth, room, booking, payment) with user
`grandvista_<svc>`, Eureka `localhost:8761`, JWKS `localhost:8081`. Services started on the host register with
their host address, so mixing host and container instances of the same service is not recommended. Note that the
auth-service is not published to the host in Compose; when running a resource service on the host, run the
auth-service on the host too (or temporarily publish port 8081).

Frontend with hot reload (proxies `/api` to `http://localhost:8080`):

```bash
npm --prefix frontend run dev
```

## Database migrations

Flyway runs automatically at every service start, before the application accepts traffic; a failing migration
stops the service (visible in `docker compose logs <svc>`). Add a new `V<n>__description.sql` file — never edit an
applied one. Check the state:

```bash
set -a; . ./.env; set +a; PGPASSWORD=$BOOKING_DB_PASSWORD psql -h localhost -p 5435 -U grandvista_booking grandvista_booking -c 'select version, description, success from flyway_schema_history'
```

## Cloud deployment

**Status: not performed.** No cloud account, registry or credentials are configured, and the CI `deploy` job is
disabled (`if: false`). The following is the recommended path.

### Target options

| Option | Fit |
|---|---|
| **Azure Container Apps / AWS ECS Fargate / Google Cloud Run** + managed PostgreSQL | simplest: each image becomes an app/service with health probes and secrets; Eureka can keep running as an internal app, or be replaced by the platform's service discovery |
| **Kubernetes** (AKS/EKS/GKE) | most control; replace Eureka/LoadBalancer with Kubernetes Services (Spring Cloud Kubernetes) and the gateway's `lb://` URIs with service DNS names |

### Steps

1. **Registry:** push the seven images (`grandvista/*:1.0.0`) to a private registry, tagged by commit SHA.
2. **Database:** provision managed PostgreSQL 16 (one instance per service in production, or one server with four
   databases for a demo); enable automated backups and point-in-time recovery; create the four owner roles (see the
   init script); require TLS (`?sslmode=require` in `DB_URL`).
3. **Secrets:** store every secret from `.env.example` and the JWT key pair in the platform's secret manager
   (Key Vault / Secrets Manager / Secret Manager); never in images or plain environment files. Rotate the JWT key by
   publishing the new public key in the JWKS before signing with it.
4. **Configuration:** `SPRING_PROFILES_ACTIVE=prod`, `API_DOCS_ENABLED=false`, `GV_DEMO_SEED_ENABLED=false`,
   `GV_CORS_ALLOWED_ORIGINS=https://<your-domain>`, `GV_TRUST_FORWARDED_FOR=true` only behind your load balancer.
5. **Networking:** expose only the frontend and the gateway through an HTTPS load balancer (TLS certificates,
   HSTS); keep services, Eureka and the database on a private network. The refresh cookie is `Secure` and
   `SameSite=Strict`, so serve the SPA and the API from the same site.
6. **Health:** liveness `/actuator/health/liveness`, readiness `/actuator/health/readiness` on each service;
   `/healthz` on the frontend.
7. **Resources:** start with 0.5 vCPU / 768 MB per JVM service (`-XX:MaxRAMPercentage=70` is set), 2 instances of
   room, booking, payment and gateway. Multiple gateway instances need a shared rate limiter (Redis-backed
   `RequestRateLimiter`) instead of the in-memory one.
8. **Migrations:** run automatically at start; for zero-downtime releases use expand–contract migrations
   (add columns first, deploy code, remove old columns in a later release).
9. **Observability:** set `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`, ship logs to the platform's log service and
   search by `correlationId`; scrape `/actuator/prometheus` from inside the private network; alert on 5xx rate,
   `outboxDead > 0`, `NEEDS_ATTENTION` bookings and overdue holds (queries in [OPERATIONS.md](OPERATIONS.md)).
10. **Payment provider:** implement `PaymentProvider` for a real provider (charge, retrieve, refund, its webhook
    signature scheme) and register it instead of the simulated one; use the provider's test mode first.
11. **Verification after deploy:** run `scripts/e2e-smoke.sh https://<gateway-host>` against a staging
    environment (it creates test users and bookings — never run it against production data).
12. **Rollback:** keep the previous image tags; roll back the application first. Migrations are forward-only, so
    a release must stay compatible with the previous schema version (expand–contract) to allow rollback.

### CI/CD

`.github/workflows/ci.yml` builds and tests the backend (with Testcontainers), lints/tests/builds the frontend,
validates Compose and builds all images, and runs an advisory dependency audit. To enable deployment, add registry
and cloud credentials as GitHub encrypted secrets, push images in the `images` job, and replace the disabled
`deploy` job with your platform's deploy action. Status: the workflow has not yet run on GitHub.
