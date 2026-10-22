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

## Razorpay test mode

The payment-service has two gateway implementations behind `PaymentProvider`, selected with
`GV_PAYMENT_PROVIDER`:

| Value | What happens | Needs |
|---|---|---|
| `simulated` (default) | built-in simulated provider: test tokens, signed webhooks, timeouts — no external calls | nothing |
| `razorpay` | Razorpay **test mode**: server-created orders, Razorpay Checkout in the browser, server-side signature check and payment lookup, capture, signed webhooks, refunds | the three keys below; live keys (`rzp_live_…`) are refused |

**Status:** the Razorpay integration is built and covered by automated tests against a fake Razorpay API
(`RazorpayFlowIntegrationTest`, 8 tests; `RazorpaySignaturesTest`, 3) and a browser test with a mocked Checkout
widget. It has **not** been run against the real Razorpay test API, because no test keys were available.

To switch on (no money moves in test mode):

1. Create a Razorpay account at https://dashboard.razorpay.com and switch the dashboard to **Test Mode**.
2. *Settings → API Keys → Generate Test Key*: put the key id (`rzp_test_…`) in `RAZORPAY_KEY_ID` and the secret in
   `RAZORPAY_KEY_SECRET` in `.env` (never commit them).
3. *Settings → Webhooks → Add New Webhook*: URL `https://<your-public-host>/api/v1/payments/webhooks/razorpay`,
   a secret of your choice (put it in `RAZORPAY_WEBHOOK_SECRET`), events `payment.captured`, `payment.failed` and
   `order.paid`. Razorpay cannot reach `localhost`; for a local test expose the gateway with a tunnel (e.g.
   `cloudflared tunnel --url http://localhost:8080`) or rely on the browser confirmation plus reconciliation
   (webhooks then only cover a closed browser).
4. Set `GV_PAYMENT_PROVIDER=razorpay` in `.env` and run `docker compose up -d` (the payment-service and the
   frontend read it; the frontend then allows Razorpay Checkout in its Content-Security-Policy).
5. Book a room and pay with a Razorpay test card or test UPI ID shown in Razorpay's test-mode documentation.

If any key is missing the payment-service stops at start-up with
`GV_PAYMENT_PROVIDER=razorpay but these settings are empty: …` (verified).

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

**Status: prepared, not performed.** No cloud account or credentials were available, so nothing has been
deployed. The production overlay (`docker-compose.prod.yml`), Caddy configuration and `scripts/deploy.sh` were
tested **on the development machine** with `GV_DOMAIN=localhost` (Caddy's local certificate authority instead of
Let's Encrypt) — see "Local test of the production overlay" below.

### Recommended target: one Oracle Cloud "Always Free" ARM VM

| Option | Verdict |
|---|---|
| **Oracle Cloud Always Free — Ampere A1 VM** (up to 4 OCPU / 24 GB RAM, free indefinitely) | **Recommended.** The only free option with enough memory for the whole stack (~3 GB measured, 23 containers); runs the same Docker Compose files, so nothing changes in the code. Trade-offs: sign-up needs a card for identity verification; free A1 capacity is sometimes temporarily unavailable in a region; you operate the VM (updates, backups). |
| AWS EC2 / Google Cloud free tier (1 GB RAM) | too small for 11 JVMs + 5 PostgreSQL servers + RabbitMQ |
| Render / Railway / Fly.io free plans | per-service memory limits and no free multi-database/broker setup for 20+ containers |
| Managed containers (Azure Container Apps, ECS Fargate, Cloud Run) + managed PostgreSQL | production-grade but not free; see "Production-grade alternative" |

### What is prepared

| File | Purpose |
|---|---|
| `docker-compose.prod.yml` | overlay: adds **Caddy** (only published ports: 80/443), removes every other host port, `SPRING_PROFILES_ACTIVE=prod` (no Swagger/API docs), CORS and e-mail links set to `https://${GV_DOMAIN}` |
| `infrastructure/docker/caddy/Caddyfile` | automatic HTTPS (Let's Encrypt) for `GV_DOMAIN`, HSTS; `/api/*` → gateway load balancer, everything else → web app |
| `scripts/deploy.sh` | on the VM: create/complete `.env`, build, start with `--wait`, then run the smoke test against `https://GV_DOMAIN` |
| `.env` keys `GV_DOMAIN`, `ACME_EMAIL` | public host name and Let's Encrypt account e-mail (`init-env.sh` adds them empty) |

### Steps (Oracle Cloud)

1. **Account** (you): create an Oracle Cloud Free Tier account at https://www.oracle.com/cloud/free/.
2. **VM**: *Compute → Instances → Create*: image **Ubuntu 24.04**, shape **VM.Standard.A1.Flex** with 4 OCPU and
   24 GB memory (Always Free eligible), upload your SSH public key, keep the public IPv4 address.
3. **Firewall**: in the instance's *Virtual Cloud Network → Security List*, add ingress rules for TCP 80 and 443
   from `0.0.0.0/0` (SSH 22 is open by default). On the VM, Ubuntu images also filter with iptables:
   `sudo iptables -I INPUT 6 -p tcp -m multiport --dports 80,443 -j ACCEPT && sudo netfilter-persistent save`.
4. **Domain**: point a host name at the VM's public IP (an A record at your DNS provider, or a free dynamic-DNS
   subdomain such as DuckDNS). Let's Encrypt needs this name to resolve before the first start.
5. **Docker**: `ssh ubuntu@<ip>`, then `curl -fsSL https://get.docker.com | sh` and `sudo usermod -aG docker ubuntu`
   (log out and in again). Install `git`, `jq` and `openssl` if missing (`sudo apt-get install -y git jq openssl`).
6. **Code**: `git clone <your GitHub repository URL> grandvista && cd grandvista`.
7. **Configure**: `./scripts/init-env.sh`, then edit `.env`: set `GV_DOMAIN=<your host name>`,
   `ACME_EMAIL=<your e-mail>`, and decide `GV_DEMO_SEED_ENABLED` (`true` loads the fictional rooms and demo accounts).
   Optionally `GV_PAYMENT_PROVIDER=razorpay` with test keys (see "Razorpay test mode"; the webhook URL becomes
   `https://<host>/api/v1/payments/webhooks/razorpay`).
8. **Deploy**: `./scripts/deploy.sh` — builds the images on the VM (ARM64), starts everything, waits for health
   checks and runs the smoke test against the public HTTPS URL. Later updates: `./scripts/deploy.sh --pull`.
9. **Operate**: `docker compose -f docker-compose.yml -f docker-compose.prod.yml logs -f <service>`; backups with
   `pg_dump` per database (see OPERATIONS.md) copied off the VM; `sudo apt-get upgrade` regularly.

Remember that the smoke test creates test users and bookings: run it only on a demo deployment.

### Local test of the production overlay (performed)

```bash
GV_DOMAIN=localhost ACME_EMAIL=ops@example.test docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build --wait
```
```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml cp caddy:/data/caddy/pki/authorities/local/root.crt /tmp/caddy-root.crt
```
```bash
CURL_CA_BUNDLE=/tmp/caddy-root.crt MAILPIT_URL= ./scripts/e2e-smoke.sh https://localhost
```

Results are recorded in [TESTING.md](../testing/TESTING.md#production-overlay-local-https-test-22-oct-2026).

### Production-grade alternative (not free)

Managed containers (Azure Container Apps / AWS ECS Fargate / Google Cloud Run) with managed PostgreSQL and a
managed broker: push the images to a registry, keep secrets in the platform's secret manager, expose only the web
app and the gateway behind an HTTPS load balancer, use readiness/liveness probes (`/actuator/health/readiness`,
`/liveness`), structured logs (`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`), and expand–contract migrations so that a
release can be rolled back.

### CI/CD

`.github/workflows/ci.yml` builds and tests the backend (Testcontainers, JaCoCo), lints/tests/builds the frontend,
builds all images, starts the full stack and runs the smoke test, and runs an advisory dependency audit. The
`deploy` job is disabled; to enable continuous deployment to the VM, add the VM's SSH key and host as GitHub
encrypted secrets and run `ssh … ./scripts/deploy.sh --pull` from that job. Status: first run on GitHub green on
23 Oct 2026 ([run 39832912584](https://github.com/maheswar-padi/grandvista-hospitality/actions/runs/39832912584)).
