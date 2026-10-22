# Operations and troubleshooting

## Observability built in

| Signal | Where |
|---|---|
| Health | `/actuator/health` (components shown, details hidden), probes `/actuator/health/liveness` and `/readiness` — used by Docker health checks; gateway `http://localhost:8080/actuator/health` is public |
| Metrics | `/actuator/prometheus` on each service (internal network only; not routed by the gateway) |
| Logs | one line per event with service name and correlation ID: `2026-10-21 12:45:55,962 INFO [booking-service] [cid=trace-demo-…] …`; set `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` for JSON |
| Access log | gateway logger `gateway.access`: method, path, status, duration, correlation ID |
| Correlation | `X-Correlation-Id` is returned to clients, shown in error responses, propagated to every downstream call and stored with outbox events |
| Business audit | `booking_status_history`, `payment_events`, `inventory_events`, `auth_audit_events` tables; admin dashboard alerts for `NEEDS_ATTENTION` bookings and undeliverable payment events |

Follow one request through all services:

```bash
docker compose logs --no-log-prefix | grep "cid=<correlation-id>"
```

Database shell for a service (credentials come from `.env`; nothing is printed):

```bash
set -a; . ./.env; set +a; PGPASSWORD=$BOOKING_DB_PASSWORD psql -h localhost -p 5435 -U grandvista_booking grandvista_booking
```

## Diagnosis guide

### A service fails to start
1. `docker compose ps` → which container is unhealthy or restarting.
2. `docker compose logs --tail=200 <service>` → look for `APPLICATION FAILED TO START`, `Caused by`.
3. Typical causes: missing environment variable (run `./scripts/init-env.sh`); database password mismatch after
   regenerating `.env` (the volume keeps the old passwords → `./scripts/reset-data.sh`); `Schema-validation:`
   (entity and migration disagree — fix the migration); out of memory (`docker stats`; raise Docker's memory).

### Database connection failures
* `Connection refused` / `UnknownHost postgres` → postgres not healthy yet; `docker compose ps postgres`.
* `password authentication failed` → see "fails to start" above.
* Pool exhaustion shows as `Connection is not available, request timed out after 5000ms`; check
  `hikaricp_connections_active` in `/actuator/prometheus` and long-running transactions (`pg_stat_activity`).

### Eureka registration problems
* Dashboard: http://localhost:8761 (HTTP Basic, credentials in `.env`). Every service should be **UP**.
* `DOWN` while the container is healthy → its `/actuator/health` reports a dependency DOWN (`db`).
* Two entries for one service right after a restart: the old lease expires within ~30 s (self-preservation is off
  in Compose).
* After the host sleeps, services re-register ("Re-registering apps" in the log) — expected.
* Note: the Eureka health *indicator* is excluded from the health published to Eureka on purpose; including it
  makes instances flap between UP and DOWN (seen during development).

### Gateway routing errors
| Response | Meaning |
|---|---|
| 503 `SERVICE_UNAVAILABLE` | no UP instance in Eureka for the route (wait ~30 s after start-up) |
| 504 `UPSTREAM_TIMEOUT` | the service took longer than 15 s |
| 404 `NOT_FOUND` | no route (internal and actuator paths are never routed) |
| 401 / 403 at the gateway | missing/invalid/expired token, wrong audience, or non-personnel on `/api/v1/admin/**` |
| 429 `RATE_LIMITED` | per-IP limit; `Retry-After` says when to retry |

### Failed booking orchestration
Find the booking and its history:
```sql
SELECT reference, status, status_reason, hold_expires_at, updated_at FROM bookings WHERE reference = 'GV-XXXXXX';
SELECT from_status, to_status, reason, actor, occurred_at FROM booking_status_history
 WHERE booking_id = (SELECT id FROM bookings WHERE reference = 'GV-XXXXXX') ORDER BY occurred_at;
```
* Stuck in `CREATED` → a dependency was down during creation; the recovery job expires it after 2 min (releasing
  the hold, voiding any payment). The customer can retry with the same Idempotency-Key earlier.
* Stuck in `PENDING_PAYMENT` past `hold_expires_at` → the payment is still `PENDING` at the provider (recovery waits),
  or the booking/payment service was down; check the payment below.
* `CANCELLATION_PENDING` → release or refund hasn't completed yet; retried every 30 s.
* `NEEDS_ATTENTION` → a refund/release was rejected; fix the cause, then *Administration → Bookings → Retry
  compensation* (or `POST /api/v1/admin/bookings/{id}/retry-compensation`).

### Payment failures
```sql
SELECT status, failure_code, provider_reference, updated_at FROM payments WHERE booking_id = '<booking-uuid>';
SELECT event_type, from_status, to_status, actor, detail, occurred_at FROM payment_events
 WHERE payment_id = '<payment-uuid>' ORDER BY occurred_at, id;
SELECT status, attempts, last_error, next_attempt_at FROM outbox_events WHERE aggregate_id = '<payment-uuid>';
```
* `PENDING` for more than a minute → reconciliation asks the provider every 20 s (`Reconciled payment …` in logs).
* Payment `SUCCEEDED` but booking not confirmed → outbox event `PENDING` with `last_error` (booking-service down)
  or `DEAD` (contract rejection: check booking-service logs). Payment summary shows `outboxPending`/`outboxDead`.
* Webhook `401 INVALID_SIGNATURE` → `PAYMENT_WEBHOOK_SECRET` differs between provider and service, or clock skew
  > 5 min.

### Events, RabbitMQ and e-mail

* Management UI: http://localhost:15672 (loopback only; user `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` from `.env`).
  Queues: `booking.payment-events`, `notification.events` and their `.dlq` dead-letter queues. All should be at 0
  messages with 2 consumers each on the main queues.
* Captured e-mails: http://localhost:8025 (Mailpit). Sent-e-mail log for staff: `GET /api/v1/admin/notifications`.
* Messages in a `.dlq`: open the queue in the UI → *Get messages* to read the payload and the `x-death` header
  (reason, original queue, count). Fix the cause, then replay by publishing the payload to exchange
  `grandvista.events` with the original routing key (consumers are idempotent, so replaying is safe).
* Producer backlog: `SELECT event_type, status, attempts, last_error FROM outbox_events WHERE status <> 'SENT';` in
  the booking or payment database. `PENDING` with `last_error = unroutable…` means a consumer queue does not
  exist yet (start that consumer); `broker unavailable` means RabbitMQ is down — events are kept and published
  when it returns.
* A payment event dead-lettered by the booking-service is also recovered by the booking recovery job, which
  checks the payment-service directly for bookings still awaiting payment after their hold expiry.

### Stuck inventory holds
Holds past their expiry that the sweeper hasn't processed (should be empty within ~30 s):
```sql
SELECT booking_id, room_id, check_in, check_out, hold_expires_at FROM inventory_allocations
 WHERE status = 'HELD' AND hold_expires_at < now() - interval '1 minute';
```
The sweeper (`HoldExpiryJob`, every 30 s, `FOR UPDATE SKIP LOCKED`) logs `Expired N lapsed inventory hold(s)`; a
new hold on the same dates also expires stale holds first, so a stuck hold never blocks a customer. Audit trail:
`SELECT * FROM inventory_events WHERE booking_id = '<uuid>' ORDER BY occurred_at`.

### Database migration errors
* `Validate failed: Migration checksum mismatch` → an applied migration file was edited; restore it and add a new
  version instead.
* `relation … already exists` → the database was created outside Flyway; reset the development database.
* Inspect: `SELECT version, description, success FROM flyway_schema_history;`

## Routine operations

| Task | How |
|---|---|
| Create staff | an administrator grants `STAFF` in *Users & roles* |
| Take a room out of service | *Rooms & inventory → Status* (staff or admin); upcoming confirmed stays are kept and reported |
| Change prices | *Pricing → Edit as new version*; existing bookings keep their price snapshot |
| Back up | one dump per service database, e.g. `docker compose exec -T booking-db pg_dump -U grandvista_booking grandvista_booking > booking.sql` (contains personal data — store securely) |
| Rotate secrets | update `.env` (or the secret store), then restart the affected services; database passwords also need `ALTER ROLE … PASSWORD` |
