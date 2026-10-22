# Architecture

This document describes the system **as implemented**. Everything described here exists in the repository and
is exercised by the tests listed in [TESTING.md](../testing/TESTING.md).

## 1. System context

```mermaid
flowchart TB
  guest([Guest / customer<br/>web browser])
  staff([Hotel staff & administrators<br/>web browser])
  subgraph GV[GrandVista Hospitality platform]
    spa[Web application]
    api[Public REST API<br/>/api/v1]
  end
  provider[[Payment provider<br/>SIMULATED in this project]]
  guest --> spa
  staff --> spa
  spa --> api
  api <-->|charges, refunds,<br/>signed webhooks| provider
```

The platform has no external dependencies at runtime other than its own PostgreSQL servers (one per service). The payment
provider is simulated inside the payment-service (clearly separated in its own schema and package) so the
complete workflow — including asynchronous webhooks and lost responses — can be demonstrated without
credentials or real money.

## 2. Service architecture

```mermaid
flowchart LR
  subgraph edge[Edge]
    nginx[frontend<br/>nginx · static SPA]
    gw[api-gateway<br/>Spring Cloud Gateway]
  end
  subgraph core[Business services]
    auth[auth-service]
    room[room-service]
    booking[booking-service]
    payment[payment-service]
    notif[notification-service]
  end
  eureka[(discovery-server<br/>Eureka)]
  mq{{RabbitMQ<br/>exchange grandvista.events}}
  smtp[Mailpit<br/>SMTP · local inbox]

  nginx -->|/api/*| gw
  gw -->|lb://auth-service| auth
  gw -->|lb://room-service| room
  gw -->|lb://booking-service| booking
  gw -->|lb://payment-service| payment
  gw -->|lb://notification-service<br/>admin only| notif

  booking -->|"POST /internal/v1/holds<br/>confirm · release"| room
  booking -->|"POST /internal/v1/payments<br/>cancel · refunds"| payment
  payment -->|"payment.* (outbox)"| mq
  booking -->|"booking.* (outbox)"| mq
  mq -->|booking.payment-events| booking
  mq -->|notification.events| notif
  notif -->|SMTP| smtp
  booking & payment -->|client credentials| auth
  gw & room & booking & payment & notif -->|JWKS| auth

  auth & room & booking & payment & notif & gw -. register/fetch .-> eureka
  auth --- d1[(auth-db)]
  room --- d2[(room-db)]
  booking --- d3[(booking-db)]
  payment --- d4[(payment-db)]
  notif --- d5[(notification-db)]
```

Each service owns a **separate PostgreSQL server** (container, volume, credentials); no service can connect to
another service's database (`scripts/verify-db-isolation.sh`). Data crosses service boundaries only through
REST APIs and events.

| Component | Responsibility | Owns |
|---|---|---|
| **frontend** | React SPA; nginx serves it and reverse-proxies `/api` so the browser sees one origin | — |
| **api-gateway** | Single public entry point: explicit routes over Eureka (`lb://`), JWT pre-validation, coarse role checks, CORS, per-IP rate limiting, request-size limit, correlation IDs, uniform problem errors, aggregated Swagger UI | — |
| **discovery-server** | Eureka registry (HTTP Basic protected); services register, gateway and services discover each other | — |
| **auth-service** | Registration, login, account lockout, RS256 access tokens + JWKS, rotating refresh tokens, roles, user administration, client-credentials tokens for services, bootstrap administrator | `grandvista_auth` |
| **room-service** | Room catalogue, room types, amenities, photos (media store), search & availability, **inventory holds/allocations**, **dynamic pricing** and policy versions, inventory audit trail, occupancy statistics | `grandvista_room` |
| **booking-service** | Reservations and their lifecycle; **orchestrates the booking saga**; cancellation policy; payment-event inbox (RabbitMQ consumer); booking-event outbox; recovery of stuck bookings | `grandvista_booking` |
| **payment-service** | Payments and their state machine, provider abstraction + simulated provider, idempotent charges, webhooks, refunds, reconciliation, **transactional outbox** publishing `payment.*` events | `grandvista_payment` |
| **notification-service** | Consumes booking and payment events; keeps a local read model of booking contacts; sends HTML e-mails (Thymeleaf) at most once per event and type; staff API listing sent e-mails | `grandvista_notification` |
| **platform-commons** | Shared library (not a service): correlation-ID filter, RFC 9457 errors, JWT validation wiring, service-token client, bounded retry, specification helpers. Contains no business logic. | — |

### Decision ownership

| Decision | Owner | Why there |
|---|---|---|
| Is the room free for these dates? | room-service | it owns allocations and the exclusion constraint |
| What does the stay cost? | room-service | it owns base rates, pricing policy and occupancy |
| May this customer book / cancel, and what is refunded? | booking-service | it owns reservations and the cancellation policy |
| Did the money move? | payment-service (from the provider) | only the provider's answer is authoritative |
| Who is the caller and what are their roles? | auth-service (token issuer); every service verifies | central identity, decentralised enforcement |

## 3. Synchronous communication

* **Discovery & load balancing.** Services register with Eureka (`eureka.client.healthcheck.enabled=true`, so
  an instance whose database is down is reported DOWN and skipped). Callers use Spring Cloud LoadBalancer
  through a `@LoadBalanced RestClient.Builder` with logical URLs such as `http://room-service`. The gateway uses
  `lb://` route URIs. Route targets can be overridden with direct URLs (`GV_ROUTE_*`) for runs without Eureka.
* **Timeouts.** Every outbound call has explicit connect/read timeouts (2 s / 5–8 s); gateway: 1 s connect,
  15 s response.
* **Retries and circuit breakers (Resilience4j).** booking-service → room/payment: at most 3 attempts,
  exponential backoff with ±20 % jitter, only for transport failures and 502/503/504 (`TransientFailure`), and
  **only for operations that are idempotent on the callee** (every internal command carries a booking id, payment
  id or idempotency key); a circuit breaker per service opens at ≥ 50 % failures and then fails fast with
  `503 DEPENDENCY_UNAVAILABLE`. 4xx responses are never retried and never open the circuit. The gateway has its own
  circuit breaker per route (fallback `503` + `Retry-After`) and retries only failed connections.
* **Contracts.** Each consumer declares its own DTOs (no shared entity classes). Example messages in
  [`/contracts`](../../contracts) are verified by both provider and consumer tests. Internal APIs are versioned
  under `/internal/v1`, public APIs under `/api/v1`; the payment event carries `schemaVersion`.
* **Service authentication.** booking-service and payment-service obtain short-lived (5 min) `SERVICE` tokens
  from the auth-service with the client-credentials grant; `/internal/**` endpoints require that role.
  `/internal/**` is never routed by the gateway.
* **Correlation.** The gateway assigns `X-Correlation-Id`; every service puts it in the logging MDC, returns it,
  forwards it on outbound calls and stores it with outbox events, so one request can be followed through all
  logs (verified: the same ID appears in gateway, booking-service and room-service logs).

## 4. Asynchronous communication (RabbitMQ)

Domain events travel through **RabbitMQ** (topic exchange `grandvista.events`), always written to a
**transactional outbox** first:

```mermaid
sequenceDiagram
  participant P as payment-service
  participant B as booking-service
  participant X as RabbitMQ (grandvista.events)
  participant N as notification-service
  participant M as Mailpit (SMTP)
  P->>P: payment SUCCEEDED + outbox row (one transaction)
  P->>X: publish payment.succeeded (publisher confirm, mandatory)
  X->>B: queue booking.payment-events
  B->>B: inbox check → confirm room → CONFIRMED + outbox row
  B->>X: publish booking.confirmed
  X->>N: queue notification.events (payment.* and booking.*)
  N->>N: upsert booking contact · insert notification (event id, type) · lock
  N->>M: send "Your stay is confirmed" and "Payment receipt"
```

1. **Outbox.** A payment or booking state change and its event row are committed in **one transaction**, so an
   event can't be lost or published for a rolled-back change. Events: `BOOKING_CREATED`, `BOOKING_CONFIRMED`,
   `BOOKING_CANCELLED`, `HOLD_EXPIRED` (booking-service) and `PAYMENT_SUCCEEDED`, `PAYMENT_FAILED`
   (payment-service). Contracts: [contracts/README.md](../../contracts/README.md#events-on-rabbitmq).
2. **Relay.** Each producer's relay claims one due row with `FOR UPDATE SKIP LOCKED` (safe with several
   replicas), publishes it as a persistent message with **publisher confirms** and `mandatory=true`, and marks it
   `SENT`. If the broker is down, refuses the message, or no queue is bound yet, the row stays `PENDING` with
   exponential backoff (up to 5 min); after 25 attempts it is `DEAD` and visible in the admin payment summary.
3. **Consumers.** Each consumer owns a durable queue with a **dead-letter queue** (`<queue>.dlq` via
   `grandvista.events.dlx`). Failures are retried in the consumer with exponential backoff (6 attempts, 1 s → 15 s)
   and then dead-lettered; malformed messages are dead-lettered immediately.
4. **Idempotency.** The booking-service records processed event ids in `processed_events` in the same
   transaction as the booking transition. The notification-service inserts `(event_id, notification_type)`
   with `ON CONFLICT DO NOTHING`, locks that row and skips it if already `SENT`, so a duplicate delivery never
   sends a second e-mail. The only remaining duplicate window is a crash after the SMTP server accepted an e-mail
   but before the commit; the stable `Message-ID` lets mail clients recognise that copy.
5. **Read model.** Payment events carry no contact data; the notification-service keeps a `booking_contacts`
   table built from booking events (event-carried state transfer) instead of calling the booking-service.
6. **Fallback.** The payment-service can still deliver directly over HTTP to the booking-service
   (`GV_EVENT_TRANSPORT=http`, no broker required). Booking and payment keep working while the broker is down:
   events wait in the outbox, and the booking recovery job reconciles unpaid bookings with the payment-service.

## 5. Booking orchestration (saga)

The booking-service orchestrates; there is no distributed transaction. Each local step is a short database
transaction under a row lock, remote calls happen between transactions, and every remote step is idempotent.

```mermaid
sequenceDiagram
  autonumber
  actor C as Customer (SPA)
  participant G as API gateway
  participant B as booking-service
  participant R as room-service
  participant P as payment-service
  participant S as Simulated provider

  C->>G: POST /api/v1/bookings (JWT, Idempotency-Key, expectedTotal)
  G->>B: route (JWT pre-validated)
  B->>B: validate dates/guests; insert booking CREATED<br/>(unique customer + key ⇒ replay returns same booking)
  B->>R: POST /internal/v1/holds {bookingId, room, dates}
  R->>R: lock room row; expire stale holds; price stay;<br/>INSERT allocation HELD (exclusion constraint)
  R-->>B: 201 hold + authoritative quote (expires in 15 min)
  alt quote.total ≠ expectedTotal
    B->>R: release hold
    B-->>C: 409 PRICE_CHANGED (no charge)
  end
  B->>B: store immutable price snapshot
  B->>P: POST /internal/v1/payments {bookingId, amount, expiresAt}
  P-->>B: 201 payment CREATED
  B->>B: CREATED → PENDING_PAYMENT
  B-->>C: 201 booking PENDING_PAYMENT (+ paymentId, holdExpiresAt)

  C->>G: POST /api/v1/payments/{id}/confirm (Idempotency-Key, test token)
  G->>P: route
  P->>P: owner check; CREATED → PENDING (tx 1)
  P->>S: charge(idempotencyKey = paymentId)
  S-->>P: SUCCEEDED / FAILED / PENDING
  P->>P: apply outcome + outbox event (tx 2)
  P-->>C: payment status
  loop every 2 s
    P->>B: POST /internal/v1/payment-events PAYMENT_SUCCEEDED
  end
  B->>R: POST /internal/v1/holds/{bookingId}/confirm
  R-->>B: allocation CONFIRMED
  B->>B: PENDING_PAYMENT → CONFIRMED (+ processed event)
  C->>G: GET /api/v1/bookings/{id} (polling)
  G-->>C: CONFIRMED (authoritative)
```

### Failure handling and compensation

| Situation | What happens | Customer impact |
|---|---|---|
| Room unavailable / not sellable / too many guests | hold refused (409/422) → booking `REJECTED` | clear error, never charged |
| Price changed since the customer's quote | hold released → `REJECTED` with `PRICE_CHANGED` | shown the new price, never charged |
| room-service or payment-service unreachable during creation | booking stays `CREATED`, 503 with `retryable=true`; retrying with the same key **resumes** (hold/payment creation are idempotent); otherwise the recovery job releases the hold, voids any payment and marks `EXPIRED` | never charged |
| Payment declined | `PAYMENT_FAILED` event → room released → booking `PAYMENT_FAILED` | told it failed, room freed |
| Customer never pays | after the hold expiry the recovery job voids the payment (only possible while `CREATED`), releases the room, booking `EXPIRED` | nothing charged |
| Payment succeeded but hold had expired | room-service tries to **re-acquire** the same dates; if still free → confirmed | booking confirmed |
| …and someone else took the room meanwhile | `INVENTORY_NO_LONGER_AVAILABLE` → booking `CANCELLATION_PENDING` with full refund → refund issued → `CANCELLED` | full refund, told why |
| Refund rejected by the provider | booking `NEEDS_ATTENTION` (visible on the dashboard); admin can *Retry compensation* | handled by staff |
| Payment event delivery fails (booking/room service down) | outbox retries with backoff; booking consumer is idempotent | confirmation delayed, not lost |
| Payment event lost entirely | recovery job's void attempt returns `PAYMENT_ALREADY_CAPTURED` → booking confirmed via the normal success path | confirmation delayed |
| Provider response lost after charging (timeout) | payment stays `PENDING`; reconciliation job asks the provider (`retrieve`) and applies the authoritative outcome | confirmation delayed |
| Duplicate client request / double click | same `Idempotency-Key` ⇒ same booking/payment attempt; provider idempotency key = payment id ⇒ never charged twice | none |
| Duplicate webhook | de-duplicated by provider event id | none |

### Booking state machine

```mermaid
stateDiagram-v2
  [*] --> CREATED
  CREATED --> PENDING_PAYMENT: hold + payment created
  CREATED --> REJECTED: unavailable / price changed
  CREATED --> EXPIRED: orchestration never completed (recovery)
  PENDING_PAYMENT --> CONFIRMED: payment succeeded + inventory confirmed
  PENDING_PAYMENT --> PAYMENT_FAILED: payment failed
  PENDING_PAYMENT --> EXPIRED: hold lapsed unpaid (recovery)
  PENDING_PAYMENT --> CANCELLED: cancelled before paying
  PENDING_PAYMENT --> CANCELLATION_PENDING: paid but inventory lost (refund)
  CONFIRMED --> CANCELLATION_PENDING: cancel (refund per policy)
  CANCELLATION_PENDING --> CANCELLED: room released + refund done
  CANCELLATION_PENDING --> NEEDS_ATTENTION: refund rejected
  NEEDS_ATTENTION --> CANCELLATION_PENDING: admin retries
  REJECTED --> [*]
  EXPIRED --> [*]
  PAYMENT_FAILED --> [*]
  CANCELLED --> [*]
```

Transitions are enforced by `BookingStatus.canTransitionTo` and applied only through `Booking.transitionTo`,
which also appends to `booking_status_history`. There is no endpoint that sets a status directly.

**Cancellation policy** (hotel-local calendar days before check-in): ≥ 7 days → 100% refund; 2–6 days → 50%;
fewer → no refund (still cancellable until the check-in date); on/after check-in → not cancellable online.
Cancellations by staff/administrators on a guest's booking are hotel-initiated → 100% refund.

## 6. Payment workflow

```mermaid
flowchart TD
  A[Booking created] -->|POST /internal/v1/payments| B[Payment CREATED<br/>amount fixed from price snapshot]
  B -->|customer confirms with test token<br/>+ Idempotency-Key| C{owner? not expired?<br/>status CREATED?}
  C -- no --> X[404 / 409 PAYMENT_EXPIRED /<br/>PAYMENT_ALREADY_PROCESSED]
  C -- yes --> D[PENDING · attempt recorded · tx committed]
  D -->|charge, key = paymentId| E{provider answer}
  E -- SUCCEEDED --> F[SUCCEEDED + outbox PAYMENT_SUCCEEDED]
  E -- FAILED --> G[FAILED + outbox PAYMENT_FAILED]
  E -- PENDING --> H[stays PENDING]
  E -- timeout --> H
  H -->|signed webhook<br/>HMAC + timestamp + dedupe| E2{event type}
  H -->|reconciliation job<br/>provider.retrieve| E2
  E2 -- charge.succeeded --> F
  E2 -- charge.failed --> G
  B -->|booking expired/cancelled before payment| V[CANCELLED (voided)]
  F -->|refund requested by booking-service| RP[REFUND_PENDING]
  RP --> RF[REFUNDED / PARTIALLY_REFUNDED]
  RP -->|provider refused| F
```

```mermaid
stateDiagram-v2
  [*] --> CREATED
  CREATED --> PENDING
  CREATED --> CANCELLED
  PENDING --> SUCCEEDED
  PENDING --> FAILED
  SUCCEEDED --> REFUND_PENDING
  PARTIALLY_REFUNDED --> REFUND_PENDING
  REFUND_PENDING --> REFUNDED
  REFUND_PENDING --> PARTIALLY_REFUNDED
  REFUND_PENDING --> SUCCEEDED: refund failed
  FAILED --> [*]
  CANCELLED --> [*]
  REFUNDED --> [*]
```

Key rules: the amount is set by the booking-service from the stored price snapshot and the confirm request has
no amount field (extra fields are ignored); a browser can never mark a payment successful — only the provider
response, a verified webhook or reconciliation can; there is no administrative endpoint that changes a payment
status (`/api/v1/admin/payments/**` is read-only and any write is denied).

## 7. Inventory consistency

Double booking is prevented by the database, not by application checks:

```sql
CONSTRAINT ex_allocations_no_overlap EXCLUDE USING gist (room_id WITH =, stay_range WITH &&)
    WHERE (status IN ('HELD', 'CONFIRMED'))
```

`stay_range` is a generated `daterange(check_in, check_out, '[)')` — the half-open interval means the check-out
day is free for the next guest. Additional safeguards:

* **Per-room serialisation.** A hold (and a confirm that may re-acquire) first takes `SELECT … FOR UPDATE` on
  the room row. Without it, two transactions inserting conflicting rows into a GiST exclusion constraint can
  wait on each other and be aborted as a deadlock (observed in testing); with it the loser waits and then gets
  a clean `ROOM_UNAVAILABLE`. Holds on *different* rooms proceed in parallel. Lock order is always room → allocation.
* **Holds expire.** A hold carries `hold_expires_at`; a sweeper (every 30 s, `SKIP LOCKED`) marks lapsed holds
  `EXPIRED`, and a new hold lazily expires stale holds on its own dates first.
* **Idempotency.** `booking_id` is unique per allocation, so retried holds return the original hold and price.
* **Status changes.** Administrators change room status under the same row lock; non-`ACTIVE` rooms cannot be
  held; retiring a room with upcoming confirmed stays is refused.
* **Optimistic locking** (`@Version`) protects concurrent edits of rooms, bookings and payments.

Verified by tests: 8 concurrent overlapping holds → exactly 1 succeeds; back-to-back stays both succeed; and,
through the full stack, 10 customers booking the same room at once → 1 accepted, 9 refused.

## 8. Dynamic pricing

```
rawNightly  = basePrice × dayMultiplier(weekend) × seasonMultiplier × demandMultiplier(room-type occupancy)
nightlyRate = round2( clamp(rawNightly, basePrice × minFactor, basePrice × maxFactor) )
subtotal    = Σ nightlyRate          serviceFee = round2(subtotal × feeRate)
taxes       = round2((subtotal + serviceFee) × taxRate)            total = subtotal + serviceFee + taxes
```

`BigDecimal` throughout, HALF_UP to 2 decimals at the points shown. Demand uses the hotel's **own** occupancy
for the room type on each night (held + confirmed allocations ÷ sellable rooms) — no external market data is
claimed. Policies are immutable versions (`pricing_policies`); exactly one is active (partial unique index);
publishing archives the previous one under a row lock. Quotes state the policy version and validity. The quote
shown in the browser is informational; the **hold** re-prices authoritatively, the booking compares it with the
customer's `expectedTotal`, and the agreed price is stored as an immutable snapshot.

## 9. Authentication architecture

```mermaid
sequenceDiagram
  participant SPA
  participant G as Gateway
  participant A as auth-service
  participant S as Any service
  SPA->>G: POST /auth/login {email, password}
  G->>A: (rate limited: 10/min/IP)
  A->>A: BCrypt verify · lockout after 5 failures
  A-->>SPA: accessToken (RS256, 15 min, in memory) + Set-Cookie gv_refresh (HttpOnly, Secure, SameSite=Strict, Path=/api/v1/auth)
  SPA->>G: GET /bookings/my  Authorization: Bearer …
  G->>G: verify signature (JWKS), iss, aud, exp, role
  G->>S: forward
  S->>S: verify token again + ownership/roles
  SPA->>G: POST /auth/refresh (cookie + X-Requested-With)
  G->>A: rotate refresh token (reuse ⇒ revoke whole family)
  A-->>SPA: new access token + new cookie
```

Details and trade-offs: [SECURITY.md](../security/SECURITY.md).

## 10. Deployment architecture (high availability)

```mermaid
flowchart TB
  user([Browser]) -->|:3000| fe[frontend · nginx<br/>SPA + /api upstream]
  client([API client · Swagger]) -->|:8080| lb[gateway-lb · nginx<br/>round robin + failover]
  fe --> gw1[api-gateway-1]
  fe --> gw2[api-gateway-2]
  lb --> gw1
  lb --> gw2
  gw1 & gw2 -->|lb:// + retry on connect failure<br/>circuit breaker per service| svc
  subgraph svc[Business services]
    auth[auth-service]
    room[room-service]
    b1[booking-service #1]
    b2[booking-service #2]
    pay[payment-service]
    notif[notification-service]
  end
  subgraph disc[Discovery]
    e1[(discovery-server-1)] <-->|peer replication| e2[(discovery-server-2)]
  end
  svc -. register / fetch (both URLs) .-> disc
  gw1 & gw2 -. fetch .-> disc
  svc --- mq{{RabbitMQ}}
  notif --- mail[Mailpit]
  svc --- dbs[(5 PostgreSQL servers)]
```

| Concern | Mechanism |
|---|---|
| Gateway failure | Two gateway instances. `gateway-lb` (public port 8080) and the frontend's nginx both balance across them with passive health checks (`max_fails=1 fail_timeout=10s`, `proxy_next_upstream error timeout`); names are re-resolved every 5 s, so a restarted gateway with a new IP rejoins. Every response carries `X-Gateway-Instance`. |
| Eureka failure | Two Eureka servers registered with each other (`EUREKA_PEER_URL`); every client lists both URLs. Registrations replicate between peers; clients keep a local registry copy, so routing continues while one server is down. |
| Service instance failure | Two booking-service replicas. Gateways cache instance lists for 5 s; a crashed instance (no deregistration) is evicted after its 15 s lease (renewal every 5 s, eviction run every 5 s). Until then the gateway's `Retry` filter re-sends a request to another instance **only** when the connection could not be made (`ConnectException`, `NoRouteToHostException`, connect timeout of 1 s) — safe for every method because the request never reached a service. Queue consumers on the dead replica lose their unacknowledged messages to the broker, which re-delivers them to the other replica. |
| Repeated failures | Resilience4j circuit breaker per downstream service in the gateway (opens at ≥ 50 % failures over the last 20 calls, minimum 10; open 10 s) with a fallback answer `503 SERVICE_UNAVAILABLE` + `Retry-After: 10`; and in the booking-service for room/payment calls (≥ 50 % of last 10, minimum 5) with retries (3 attempts, exponential backoff with jitter, transient failures only). |
| Timeouts | HTTP client timeouts everywhere (gateway: connect 1 s, response 15 s; booking → room/payment: connect 2 s, read 5–8 s); the gateway's circuit-breaker time limiter (16 s) is deliberately above the response timeout. |
| Health | `/actuator/health/liveness` and `/readiness` on every Java service (Docker health checks use readiness), `/healthz` (frontend) and `/lb-health` (load balancer). Start order enforced with `depends_on: condition: service_healthy`. |

Single instances remain for auth-, room-, payment- and notification-service, RabbitMQ, Mailpit and each PostgreSQL
server (they could be replicated the same way; memory on the demonstration laptop is the limit). The in-memory
rate limit is per gateway instance, so with two gateways a client can make up to twice the configured rate.
Verification: `scripts/failover-demo.sh` (results in [TESTING.md](../testing/TESTING.md)).

## 11. Technology decisions

| Decision | Rationale | Alternatives considered |
|---|---|---|
| Spring Boot **3.5** + Spring Cloud **2025.0** | Directive requires Boot 3.x; 2025.0 is the matching release train (verified by build) | Boot 4 (not 3.x) |
| Gateway **server-webflux** | Current Spring Cloud Gateway artifact; non-blocking edge; `spring.cloud.gateway.server.webflux.*` properties verified against the jar metadata | Gateway MVC |
| **RS256 + JWKS** | Only the auth-service can sign; others verify with the public key; keys rotate without redeploying services | HS256 shared secret (every service could mint tokens) |
| Refresh token in **HttpOnly cookie**, access token **in memory** | Tokens unreadable by injected script; short access lifetime; CSRF closed by SameSite=Strict + required header | localStorage (XSS-exposed); cookie-only session (needs full CSRF tokens) |
| **PostgreSQL exclusion constraint** | Correct under any concurrency, for every code path | application check-then-insert (racy), Redis locks (extra infra, still racy on failure) |
| **Outbox + HTTP relay** | At-least-once without operating a broker | RabbitMQ/Kafka (more infrastructure for two services) |
| **Orchestrated saga** in booking-service | One place to read the workflow and its compensations | Choreography (logic spread across services) |
| Flyway, `ddl-auto=validate` | Explicit, reviewed schema; startup fails if code and schema diverge (caught two type mismatches during development) | Hibernate auto DDL |
| Specifications for optional filters | `(:p IS NULL OR …)` JPQL fails in PostgreSQL for null dates/UUIDs (found in live testing); specifications add only present predicates | native SQL per combination |
| Virtual threads (`spring.threads.virtual.enabled`) | Cheap blocking I/O for many concurrent requests on servlet services | larger platform-thread pools |
