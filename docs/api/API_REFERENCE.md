# API reference

All public endpoints are reached **through the API gateway** at `http://localhost:8080/api/v1` (or through the web
app's same-origin proxy at `http://localhost:3000/api/v1`). Interactive documentation for every service is served
by the gateway in development: **http://localhost:8080/swagger-ui.html** (select the service in the top-right list).

The endpoint inventory below was extracted from the controller source code (`services/*/src/main/java/**/web/*Controller.java`).

## 1. Conventions

| Topic | Rule |
|---|---|
| Format | JSON (`application/json`); errors are `application/problem+json` |
| Versioning | public `/api/v1/**`; service-to-service `/internal/v1/**` (never routed by the gateway); payment events carry `schemaVersion` |
| Authentication | `Authorization: Bearer <access token>` (RS256 JWT, 15 min). Obtained from `/auth/login`, `/auth/register` or `/auth/refresh` |
| Roles | `CUSTOMER` (self-registered), `STAFF`, `ADMIN`; `SERVICE` exists only for service tokens |
| Ownership | a customer asking for someone else's booking or payment receives **404**, so identifiers cannot be probed |
| Idempotency | `POST /bookings` and `POST /payments/{id}/confirm` **require** `Idempotency-Key` (8–80 chars `[A-Za-z0-9_-]`). Repeating a request with the same key returns the original result; reusing a key with a different body returns 422 `IDEMPOTENCY_KEY_REUSED` |
| Cookie endpoints | `/auth/refresh` and `/auth/logout` use the `gv_refresh` HttpOnly cookie and require `X-Requested-With: XMLHttpRequest` |
| Correlation | send `X-Correlation-Id` (8–64 safe chars) or one is generated; it is returned on every response and appears in all service logs |
| Pagination | `page` (0-based) and `size`; responses are `{content, page, size, totalElements, totalPages}` |
| Dates | hotel-local calendar dates `yyyy-MM-dd` (zone `Asia/Kolkata`); a stay is the half-open interval `[checkIn, checkOut)` |
| Money | JSON numbers with two decimals, currency `INR`; always calculated by the server |
| Rate limits | per client IP at the gateway: 10/min for login, register and refresh; 300/min for everything else → 429 `RATE_LIMITED` with `Retry-After` |
| Size limit | request bodies above 6 MB → 413 `PAYLOAD_TOO_LARGE` |

### Error format (RFC 9457)

```json
{
  "type": "urn:grandvista:problem:validation-failed",
  "title": "Bad Request",
  "status": 400,
  "detail": "One or more fields are invalid.",
  "instance": "/api/v1/bookings",
  "code": "VALIDATION_FAILED",
  "timestamp": "2026-10-22T08:15:02.441Z",
  "correlationId": "3f2b8c1e-aaaa-bbbb-cccc-1234567890ab",
  "errors": [ { "field": "guests", "message": "must be greater than or equal to 1" } ]
}
```

`code` is stable and meant for programs; `detail` is a safe human-readable message. Stack traces and internal
messages are never returned (unexpected errors are 500 `INTERNAL_ERROR` with the correlation ID to quote).

Common codes: `VALIDATION_FAILED` (400), `AUTHENTICATION_REQUIRED` (401), `ACCESS_DENIED` (403), `*_NOT_FOUND`
(404), `CONCURRENT_MODIFICATION` / `DATA_CONFLICT` (409), `RATE_LIMITED` (429), `SERVICE_UNAVAILABLE` /
`DEPENDENCY_UNAVAILABLE` (503), `UPSTREAM_TIMEOUT` (504).

## 2. Gateway routes

| Route id | Path predicates | Target (default) |
|---|---|---|
| `auth` | `/api/v1/auth/**`, `/api/v1/admin/users/**` | `lb://auth-service` |
| `rooms` | `/api/v1/rooms/**`, `/api/v1/room-types/**`, `/api/v1/amenities/**`, `/api/v1/media/**`, `/api/v1/admin/rooms/**`, `/api/v1/admin/room-types/**`, `/api/v1/admin/amenities/**`, `/api/v1/admin/pricing/**`, `/api/v1/admin/inventory/**` | `lb://room-service` |
| `bookings` | `/api/v1/bookings`, `/api/v1/bookings/**`, `/api/v1/admin/bookings/**` | `lb://booking-service` |
| `payments` | `/api/v1/payments/**`, `/api/v1/admin/payments/**` | `lb://payment-service` |
| `notifications` | `/api/v1/admin/notifications` | `lb://notification-service` |
| `docs-*` | `/api-docs/{auth,room,booking,payment,notification}-service` → `/v3/api-docs` | each service (development only) |

Targets can be overridden with `GV_ROUTE_AUTH`, `GV_ROUTE_ROOM`, `GV_ROUTE_BOOKING`, `GV_ROUTE_PAYMENT`,
`GV_ROUTE_NOTIFICATION` (e.g. a
direct URL when running without Eureka). Anything else — including `/internal/**` and service actuators — returns
404 at the gateway. Edge rules: public `GET` catalogue paths and the credential/webhook `POST`s are anonymous;
`/api/v1/admin/**` requires `STAFF` or `ADMIN`; every other `/api/v1/**` path requires a valid token. Services
then apply their own, finer rules (listed per endpoint below).

## 3. Endpoint inventory

### 3.1 auth-service

| Method | Path | Purpose | Role | Request | Success | Important failures |
|---|---|---|---|---|---|---|
| POST | `/auth/register` | Create a customer account and start a session | public | `{email, password, fullName, phone?}` | 201 `AuthResponse` + `Set-Cookie: gv_refresh` | 400 validation (password ≥ 10 chars with letter and digit), 409 `EMAIL_ALREADY_REGISTERED`, 429 |
| POST | `/auth/login` | Sign in | public | `{email, password}` | 200 `AuthResponse` + cookie | 401 `INVALID_CREDENTIALS` (same message for unknown e-mail), 429 `ACCOUNT_TEMPORARILY_LOCKED` after 5 failures (15 min), 403 `ACCOUNT_DISABLED` |
| POST | `/auth/refresh` | New access token; rotates the refresh cookie | cookie + `X-Requested-With` | — | 200 `AuthResponse` + new cookie | 401 `INVALID_REFRESH_TOKEN` (expired, revoked, or reused ⇒ whole family revoked), 403 `CSRF_HEADER_MISSING` |
| POST | `/auth/logout` | Revoke the session and clear the cookie | cookie + `X-Requested-With` | — | 204 | 403 `CSRF_HEADER_MISSING` |
| GET | `/auth/me` | Current profile | any user | — | 200 `User` | 401 |
| GET | `/oauth2/jwks` | Public signing keys (JWKS) | public (not routed by the gateway; used by services) | — | 200 JWK set (public key only) | — |
| GET | `/admin/users?q&page&size` | Search users | ADMIN | — | 200 page of `User` | 403 |
| GET | `/admin/users/{userId}` | One user | ADMIN | — | 200 `User` | 404 |
| PUT | `/admin/users/{userId}/roles` | Replace roles (revokes the user's sessions) | ADMIN | `{roles: [..]}` | 200 `User` | 422 `CANNOT_REMOVE_OWN_ADMIN` |
| PUT | `/admin/users/{userId}/status` | Enable/disable | ADMIN | `{enabled}` | 200 `User` | 422 `CANNOT_DISABLE_SELF` |
| POST | `/internal/v1/service-tokens` | Client-credentials token for a service | HTTP Basic client credentials | form `grant_type=client_credentials` | 200 `{access_token, token_type, expires_in}` | 401 `INVALID_CLIENT`, 400 `UNSUPPORTED_GRANT_TYPE` |

`AuthResponse`: `{accessToken, tokenType: "Bearer", expiresIn: 900, user: {id, email, fullName, phone, roles, enabled, createdAt, lastLoginAt}}`.
The `roles` field of the registration request is ignored — every self-registered account is `CUSTOMER`.

### 3.2 room-service

| Method | Path | Purpose | Role | Notes / success | Important failures |
|---|---|---|---|---|---|
| GET | `/rooms` | Search rooms | public | query: `checkIn, checkOut, guests, roomType, minPrice, maxPrice, amenities (repeatable), sort (RECOMMENDED, PRICE_ASC, PRICE_DESC, CAPACITY_DESC, NAME_ASC), page, size≤50`. With dates only rooms free for the whole stay are returned, each with `stayPrice` | 400 `INVALID_DATE_RANGE`, `CHECK_IN_IN_PAST`, `STAY_TOO_LONG` (> 30 nights), `BOOKING_WINDOW_EXCEEDED` (> 365 days), `INVALID_PRICE_RANGE` |
| GET | `/rooms/{roomId}` | Room details | public | 200 `Room` | 404 `ROOM_NOT_FOUND` (also for retired rooms) |
| GET | `/rooms/{roomId}/availability?checkIn&checkOut` | Free for the whole stay? | public | `{available, reason}` (`ROOM_MAINTENANCE`, `BOOKED_FOR_SELECTED_DATES`, …) | 400 date errors |
| GET | `/rooms/{roomId}/calendar?from&days≤120` | Unavailable nights | public | `{unavailableNights: [dates]}` — no booking identities | 400 |
| GET | `/rooms/{roomId}/quote?checkIn&checkOut&guests` | Itemised dynamic price | public | `{available, unavailableReason, quote: PriceQuote}` | 422 `GUEST_COUNT_EXCEEDS_CAPACITY`, 400 dates |
| GET | `/room-types` | Room types | public | list | — |
| GET | `/amenities` | Amenities | public | list | — |
| GET | `/media/{key}` | Room image | public | binary, `Cache-Control: public, max-age=2592000, immutable`, `nosniff` | 404 (also for any malformed / traversal key) |
| GET | `/admin/rooms?status&q&page&size` | All rooms, any status | STAFF, ADMIN | page of `Room` | 403 |
| GET | `/admin/rooms/{roomId}` | Room in any status | STAFF, ADMIN | `Room` | 404 |
| POST | `/admin/rooms` | Create room | ADMIN | `RoomRequest` → 201 | 409 `ROOM_NUMBER_TAKEN`, 422 `UNKNOWN_AMENITY`/`UNKNOWN_ROOM_TYPE` |
| PUT | `/admin/rooms/{roomId}` | Update details / base rate | ADMIN | `RoomRequest` incl. current `version` | 409 `CONCURRENT_MODIFICATION`, 422 `ROOM_NUMBER_IMMUTABLE` |
| PUT | `/admin/rooms/{roomId}/status` | ACTIVE / MAINTENANCE / OUT_OF_SERVICE / INACTIVE | STAFF, ADMIN | `{status, reason}` → `{room, futureConfirmedStays, warning}` | 409 `ROOM_HAS_UPCOMING_STAYS` (retiring a room with stays) |
| POST | `/admin/rooms/{roomId}/images` | Upload photo (multipart `file`, `altText`) | ADMIN | 201 image; JPEG/PNG/WebP detected by content, ≤ 5 MB, ≤ 10 per room | 415 `UNSUPPORTED_IMAGE_TYPE`, 413 `FILE_TOO_LARGE`, 422 `TOO_MANY_IMAGES` |
| DELETE | `/admin/rooms/{roomId}/images/{imageId}` | Delete photo | ADMIN | 204 | 404 `IMAGE_NOT_FOUND` |
| POST / PUT | `/admin/room-types`, `/admin/room-types/{id}` | Manage room types | ADMIN | 201 / 200 | 409 / 422 |
| POST / PUT | `/admin/amenities`, `/admin/amenities/{id}` | Manage amenities | ADMIN | 201 / 200 | 409 / 422 |
| GET | `/admin/pricing/policy` | Active pricing policy | STAFF, ADMIN | `PricingPolicy` | — |
| GET | `/admin/pricing/policies` | All versions | STAFF, ADMIN | list, newest first | — |
| POST | `/admin/pricing/policies` | Publish a new version | ADMIN | `PolicyRequest` → 201 | 422 `OVERLAPPING_SEASONS`, `MISSING_BASE_DEMAND_TIER`, `DUPLICATE_DEMAND_TIER`, `INVALID_PRICE_LIMITS`; 400 ranges |
| GET | `/admin/inventory/summary` | Rooms by status, occupancy tonight, arrivals/departures | STAFF, ADMIN | `InventorySummary` | — |
| GET | `/admin/inventory/occupancy?from&to` | Hotel-wide occupancy per night | STAFF, ADMIN | list | 400 range > 366 days |
| GET | `/admin/inventory/allocations?roomId&status&from&to&page&size` | Holds / allocations | STAFF, ADMIN | page | — |
| GET | `/admin/inventory/events?roomId&bookingId&page&size` | Inventory audit trail | STAFF, ADMIN | page | — |
| POST | `/internal/v1/holds` | Place a hold and price it (idempotent per `bookingId`) | SERVICE | `{bookingId, roomId, checkIn, checkOut, guests}` → 201 `HoldResponse` | 409 `ROOM_UNAVAILABLE`, `ROOM_NOT_AVAILABLE`, `HOLD_MISMATCH`, `HOLD_IN_PROGRESS`; 422 capacity |
| GET | `/internal/v1/holds/{bookingId}` | Hold state | SERVICE | `HoldResponse` | 404 |
| POST | `/internal/v1/holds/{bookingId}/confirm` | Confirm after payment (re-acquires an expired hold if still free) | SERVICE | `HoldResponse` | 409 `INVENTORY_NO_LONGER_AVAILABLE`, `HOLD_RELEASED` |
| POST | `/internal/v1/holds/{bookingId}/release` | Release (idempotent; unknown booking = no-op) | SERVICE | `{reason}` → 204 | — |

### 3.3 booking-service

| Method | Path | Purpose | Role | Request / success | Important failures |
|---|---|---|---|---|---|
| POST | `/bookings` | Create booking: hold + price check + payment set-up | CUSTOMER | header `Idempotency-Key`; body `{roomId, checkIn, checkOut, guests, primaryGuest {fullName, email, phone?}, specialRequests?, expectedTotal?}` → 201 `Booking` in `PENDING_PAYMENT` (200 on replay) | 409 `ROOM_UNAVAILABLE`, `PRICE_CHANGED` (`currentTotal`), 422 `IDEMPOTENCY_KEY_REUSED`, `GUEST_COUNT_EXCEEDS_CAPACITY`, 400 dates/validation, 503 `INVENTORY_SERVICE_UNAVAILABLE` / `PAYMENT_SERVICE_UNAVAILABLE` (`retryable: true` — retry with the same key resumes) |
| GET | `/bookings/my?scope=UPCOMING\|PAST\|ALL&page&size` | Caller's bookings | any user | page of `Booking` | 400 invalid scope/size |
| GET | `/bookings/{id}` | One booking | owner, STAFF, ADMIN | `Booking` | 404 (also for other customers' bookings) |
| GET | `/bookings/{id}/status` | Lightweight authoritative status for polling | owner, STAFF, ADMIN | `{status, statusReason, paymentId, holdExpiresAt, updatedAt}` | 404 |
| GET | `/bookings/{id}/history` | Status history (audit trail) | owner, STAFF, ADMIN | list of `{fromStatus, toStatus, reason, actor, occurredAt}` | 404 |
| GET | `/bookings/{id}/cancellation-quote` | Refund the policy would give now | owner, STAFF, ADMIN | `{allowed, refundPercent, refundAmount, daysBeforeCheckIn, rule}` | 404 |
| POST | `/bookings/{id}/cancel` | Cancel (policy refund; staff on a guest booking = full refund) | owner, STAFF, ADMIN | `{reason?}` → 200 `CANCELLED` (202 while `CANCELLATION_PENDING`) | 409 `CANCELLATION_WINDOW_CLOSED`, `PAYMENT_IN_PROGRESS`, `PAYMENT_ALREADY_COMPLETED`, `BOOKING_NOT_CANCELLABLE` |
| GET | `/admin/bookings?status&from&to&q&page&size` | Search bookings | STAFF, ADMIN | page | — |
| GET | `/admin/bookings/summary` | Counts, confirmed value, arrivals, 30-day trend | STAFF, ADMIN | `BookingSummary` | — |
| POST | `/admin/bookings/{id}/retry-compensation` | Retry a failed refund/release | ADMIN | `Booking` | 409 `NOT_NEEDING_ATTENTION` |
| POST | `/internal/v1/payment-events` | Apply a payment event (idempotent by `eventId`) | SERVICE | `PaymentEventMessage` → `{result: APPLIED\|DUPLICATE\|IGNORED}` | 503 when a dependency is down (the outbox retries) |

`Booking` fields: `id, reference (GV-XXXXXX), status, statusReason, room {id, roomNumber, name, typeName}, checkIn,
checkOut, nights, guests, primaryGuest, specialRequests, price {nights, subtotal, serviceFee, taxes, total, currency,
pricingPolicyVersion, quotedAt, nightlyRates[]}, paymentId, holdExpiresAt, refundAmount, allowedActions [PAY, CANCEL],
createdAt, confirmedAt, cancelledAt, customerId, customerEmail`.

### 3.4 payment-service

| Method | Path | Purpose | Role | Request / success | Important failures |
|---|---|---|---|---|---|
| GET | `/payments/test-methods` | Test tokens of the SIMULATED provider | public | list `{token, label, expectedOutcome}` | — |
| POST | `/payments/{paymentId}/checkout` | Start the provider checkout; Razorpay: creates the order once (repeat calls return the same order) | owner (CUSTOMER) | → 200 `{paymentId, provider (SIMULATED\|RAZORPAY), testMode, keyId, orderId, amount, amountMinorUnits, currency, merchantName, description, bookingReference, prefillEmail, expiresAt}` — `keyId` is the publishable key, never a secret | 404, 409 `PAYMENT_EXPIRED` / `PAYMENT_ALREADY_PROCESSED`, 503 `PAYMENT_PROVIDER_UNAVAILABLE` |
| POST | `/payments/{paymentId}/confirm` | Authorise: simulated token **or** Razorpay Checkout result | owner (CUSTOMER) | header `Idempotency-Key`; either `{paymentMethodToken}` (simulated) or `{razorpayPaymentId, razorpayOrderId, razorpaySignature}` (Razorpay; the signature is verified and the payment is then looked up at Razorpay) — no amount field → 200 `Payment` (`SUCCEEDED`, `FAILED` or `PENDING`) | 400 `VALIDATION_FAILED`, `CHECKOUT_RESULT_REQUIRED`, `PAYMENT_METHOD_TOKEN_REQUIRED`; 404 (not owner); 409 `PAYMENT_EXPIRED`, `PAYMENT_IN_PROGRESS`, `PAYMENT_ALREADY_PROCESSED`, `CHECKOUT_NOT_STARTED` |
| GET | `/payments/{paymentId}` | Payment with refunds and audit events | owner, STAFF, ADMIN | `{payment, refunds[], events[]}` | 404 |
| GET | `/payments/by-booking/{bookingId}` | Payment of a booking | owner, STAFF, ADMIN | `Payment` | 404 |
| GET | `/payments/my?page&size` | Caller's payments | any user | page | — |
| POST | `/payments/webhooks/simulated` | Provider callback | HMAC signature (`X-Provider-Signature: t=…,v1=…`) | `{result: PROCESSED\|DUPLICATE\|IGNORED}` | 401 `INVALID_SIGNATURE` (bad/old signature), 400 `MALFORMED_WEBHOOK`, 503 `PAYMENT_NOT_YET_KNOWN` (provider retries) |
| POST | `/payments/webhooks/razorpay` | Razorpay callback (only when `GV_PAYMENT_PROVIDER=razorpay`, else 404) | `X-Razorpay-Signature` = HMAC-SHA256(raw body, webhook secret); de-duplicated by `X-Razorpay-Event-Id` | `{result: PROCESSED\|DUPLICATE\|IGNORED}`; `payment.captured`/`order.paid` → SUCCEEDED (or, for a voided/failed payment, an automatic full refund); `payment.failed` → recorded only | 401 `INVALID_SIGNATURE`, 400 `MALFORMED_WEBHOOK`, 503 `PROVIDER_UNAVAILABLE` (orphan refund could not reach Razorpay; Razorpay redelivers) |
| GET | `/admin/payments?status&bookingId&page&size` | Search payments | STAFF, ADMIN | page | — |
| GET | `/admin/payments/summary` | Totals by status, outbox health | STAFF, ADMIN | `PaymentSummary` | — |
| any write | `/admin/payments/**` | — | **denied to everyone** | 403 | payment outcomes cannot be edited by people |
| POST | `/internal/v1/payments` | Create payment for a booking (idempotent per `bookingId`) | SERVICE | `{bookingId, bookingReference, customerId, amount, currency, expiresAt}` → 201 | 409 `PAYMENT_TERMS_MISMATCH` |
| GET | `/internal/v1/payments/by-booking/{bookingId}` | Payment of a booking | SERVICE | `Payment` | 404 `PAYMENT_NOT_FOUND` |
| POST | `/internal/v1/payments/{id}/cancel` | Void an unpaid payment (idempotent) | SERVICE | `{reason}` → `Payment` `CANCELLED` | 409 `PAYMENT_IN_PROGRESS` / `PAYMENT_ALREADY_CAPTURED` with `paymentStatus` |
| POST | `/internal/v1/payments/{id}/refunds` | Refund (idempotent per key) | SERVICE | `{amount, reason, idempotencyKey}` → `Refund` | 409 `PAYMENT_NOT_REFUNDABLE`, `REFUND_KEY_REUSED`; 422 `REFUND_EXCEEDS_CAPTURED` |

Simulated test tokens: `sim_card_success`, `sim_card_success_mastercard` (approved), `sim_card_declined`,
`sim_card_insufficient_funds` (declined), `sim_card_async_success`, `sim_card_async_decline` (decided by a signed
webhook ~3 s later), `sim_card_timeout_success` (approved, but the response is "lost"; the reconciliation job
resolves it within about a minute). Any other token is declined with `invalid_payment_method`.

### 3.5 notification-service

| Method | Path | Purpose | Role | Request / success | Important failures |
|---|---|---|---|---|---|
| GET | `/admin/notifications?bookingId&page&size` | E-mails sent, newest first (optionally for one booking) | STAFF, ADMIN | page of `{id, type, eventId, bookingId, bookingReference, recipient, subject, status, createdAt, sentAt}` | 401, 403 |

The service has no other HTTP API: it is driven by RabbitMQ events (see [contracts/README.md](../../contracts/README.md#events-on-rabbitmq)).
E-mail types: `BOOKING_CONFIRMED`, `PAYMENT_RECEIPT`, `BOOKING_CANCELLED`, `PAYMENT_FAILED`, `HOLD_EXPIRED`.
Locally every e-mail is captured by Mailpit (http://localhost:8025); nothing reaches real mailboxes.

### 3.6 Events (RabbitMQ, not HTTP)

| Routing key | Producer | Payload |
|---|---|---|
| `booking.created`, `booking.confirmed`, `booking.cancelled`, `booking.hold-expired` | booking-service | [booking-event.json](../../contracts/booking-service/booking-event.json) |
| `payment.succeeded`, `payment.failed` | payment-service | [payment-event.json](../../contracts/payment-service/payment-event.json) (now includes `provider`) |

## 4. Worked example (curl)

```bash
API=http://localhost:8080/api/v1
```

Register (the response contains `accessToken`; `-c` stores the refresh cookie):
```bash
curl -s -c jar.txt -X POST $API/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"guest@example.test","password":"correct-horse-42","fullName":"Asha Rao"}'
```

Search two nights for two guests:
```bash
curl -s "$API/rooms?checkIn=2027-03-10&checkOut=2027-03-12&guests=2&sort=PRICE_ASC&size=3"
```

Quote a room (replace `$ROOM`):
```bash
curl -s "$API/rooms/$ROOM/quote?checkIn=2027-03-10&checkOut=2027-03-12&guests=2"
```
```json
{ "roomId": "…", "guests": 2, "available": true, "unavailableReason": null,
  "quote": { "currency": "INR", "nights": 2,
    "nightlyRates": [ { "date": "2027-03-10", "baseRate": 4500.00, "dayMultiplier": 1, "seasonName": null,
                        "seasonMultiplier": 1, "occupancy": 0.0000, "demandMultiplier": 1.0000, "limitApplied": null, "rate": 4500.00 },
                      { "date": "2027-03-11", "baseRate": 4500.00, "rate": 4500.00, "…": "…" } ],
    "subtotal": 9000.00, "serviceFee": 450.00, "taxes": 1134.00, "total": 10584.00,
    "pricingPolicyVersion": 1, "quotedAt": "…", "validUntil": "…" } }
```

Book (the server re-prices; `expectedTotal` guarantees you are never charged a different amount):
```bash
curl -s -X POST $API/bookings -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d "{\"roomId\":\"$ROOM\",\"checkIn\":\"2027-03-10\",\"checkOut\":\"2027-03-12\",\"guests\":2,
       \"primaryGuest\":{\"fullName\":\"Asha Rao\",\"email\":\"guest@example.test\"},\"expectedTotal\":10584.00}"
```
→ `201` with `"status":"PENDING_PAYMENT"`, `"paymentId"` and `"holdExpiresAt"` (15 minutes).

Pay with a simulated card, then poll the authoritative status:
```bash
curl -s -X POST $API/payments/$PAYMENT/confirm -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" -d '{"paymentMethodToken":"sim_card_success"}'
```
```bash
curl -s $API/bookings/$BOOKING/status -H "Authorization: Bearer $TOKEN"
```
→ `"status":"CONFIRMED"` once the payment event has been applied (normally within 2–3 s).

Cancellation preview and cancellation:
```bash
curl -s $API/bookings/$BOOKING/cancellation-quote -H "Authorization: Bearer $TOKEN"
```
```bash
curl -s -X POST $API/bookings/$BOOKING/cancel -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{}'
```

Refresh the session from the cookie:
```bash
curl -s -b jar.txt -c jar.txt -X POST $API/auth/refresh -H 'X-Requested-With: XMLHttpRequest'
```

`scripts/e2e-smoke.sh` runs these and 40 further checks automatically.
