# Requirement traceability matrix — PS016

Status legend:
**VERIFIED** = implemented, covered by automated tests **and** observed on the running stack ·
**VERIFIED (tests)** = implemented and covered by automated tests only ·
**IMPLEMENTED, NOT VERIFIED** · **PARTIAL** · **NOT IMPLEMENTED** · **EXTERNAL DEPENDENCY**.
"e2e" = `scripts/e2e-smoke.sh` (52/52 on 22 Oct 2026); "audit" = `scripts/consistency-audit.mjs` (passed 22 Oct);
test class names refer to [TESTING.md](testing/TESTING.md) (148 backend + 24 frontend tests passing).

## 1. Core requirements of the brief

| # | Requirement | Service / module | Main source | API | Data | Automated tests | Live evidence | Status |
|---|---|---|---|---|---|---|---|---|
| R1 | **JWT authentication for users and administrators** | auth-service, platform-commons, gateway | `TokenIssuer`, `SigningKeyConfig`, `AuthController`, `JwtSupport`, gateway `SecurityConfig` | `/auth/register`, `/auth/login`, `/auth/refresh`, `/auth/logout`, `/auth/me`, `/oauth2/jwks` | `users`, `user_roles`, `refresh_tokens` | `AuthFlowIntegrationTest` (11), `GatewayIntegrationTest` (expired / wrong-audience / tampered tokens) | e2e §2, §10 (admin login); browser session restore | **VERIFIED** |
| R2 | User and administrator **roles** | auth + every service | `Role`, `Roles`, per-service `SecurityConfig` | `/admin/**` | `user_roles` | `authorizationIsEnforcedPerEndpoint`, `roleBoundariesAreEnforced`, `accessRulesAndValidation`, `adminPathsRequirePersonnelRoles`, `bootstrapAdminCanManageRolesButNotDemoteSelf` | e2e: customer → admin API 403; admin pages in browser | **VERIFIED** |
| R3 | **Room Service** (inventory, types, amenities, photos, status, search) | room-service | `RoomCatalogService`, `RoomQueryService`, `AdminRoomController`, `MediaStorage` | `/rooms/**`, `/room-types`, `/amenities`, `/media/**`, `/admin/rooms/**` | `rooms`, `room_types`, `amenities`, `room_images` | `RoomInventoryIntegrationTest` (14), `MediaStorageTest` | e2e §1, §10; browser admin editor | **VERIFIED** |
| R4 | **Room availability** for a date range (half-open `[in, out)`) | room-service | `RoomQueryService.availability/search`, `StayDates`, `StayRules` | `/rooms?checkIn&checkOut`, `/rooms/{id}/availability`, `/calendar` | `inventory_allocations.stay_range` | `StayDatesTest`, `searchExcludesRoomsBookedForOverlappingDates…`, `publicEndpointsValidateDates…` | e2e §1, §4, §7 | **VERIFIED** |
| R5 | **Dynamic pricing** (weekend, season, demand, limits, fee, tax, snapshot) | room-service (+ booking snapshot) | `PricingEngine`, `PricingService`, `PricingPolicyService` | `/rooms/{id}/quote`, `/admin/pricing/**` | `pricing_policies`, `pricing_seasons`, `pricing_demand_tiers`, `booking_price_snapshots` | `PricingEngineTest` (8), `PricingPolicyValidationTest` | e2e §3 (total = parts); browser breakdown (weekend ×1.15) | **VERIFIED** |
| R6 | **Booking Service** (create, read, history, cancel, lifecycle, audit) | booking-service | `BookingOrchestrator`, `CancellationService`, `BookingQueryService`, `BookingStatus` | `/bookings/**`, `/admin/bookings/**` | `bookings`, `booking_status_history`, `booking_guests` | `BookingSagaIntegrationTest` (15), `BookingRulesTest` (24) | e2e §4–§9; browser journey | **VERIFIED** |
| R7 | **Payment Service** (attempts, states, callbacks, refunds, history, audit) | payment-service | `PaymentService`, `RefundService`, `WebhookService`, `SimulatedPaymentProvider` | `/payments/**`, `/admin/payments/**`, `/internal/v1/payments/**` | `payments`, `payment_attempts`, `refunds`, `payment_events`, `webhook_events` | `PaymentFlowIntegrationTest` (15), `PaymentStatusTest` (18), `WebhookSignatureTest` | e2e §6–§9 | **VERIFIED** (provider simulated — see R25) |
| R8 | **API Gateway** (routing, security, CORS, limits, errors) | api-gateway | `application.yml` routes, `SecurityConfig`, `RateLimitFilter`, `RequestSizeFilter`, `GatewayErrorHandler` | all `/api/v1/**` | — | `GatewayIntegrationTest` (9) | all live tests run through it; Swagger aggregation checked | **VERIFIED** |
| R9 | **Eureka Server** | discovery-server | `DiscoveryServerApplication`, `SecurityConfig` | `/eureka/**` (Basic auth) | — | `DiscoveryServerApplicationTests` (2) | dashboard: 5 apps UP | **VERIFIED** |
| R10 | **Service registration** | all services | `eureka.client.*` config | — | — | (startup in tests runs with Eureka disabled) | Eureka API lists all services UP; re-registration after host sleep observed | **VERIFIED (live)** |
| R11 | **Gateway routing** | api-gateway | routes `auth`, `rooms`, `bookings`, `payments`, `docs-*` | — | — | `publicCatalogueIsRoutedWithCorrelationId`, `protectedRoutes…`, `internalAndActuatorPathsAreNeverExposed` | e2e (all calls via gateway) | **VERIFIED** |
| R12 | **Load balancing** | gateway (`lb://`), booking/payment (`@LoadBalanced RestClient`) | `AppConfig` (booking, payment), gateway routes | — | — | — (no multi-instance automated test) | `--scale room-service=2`: gateway 30/30, booking→room 4/4; e2e passed with 2 instances ([evidence](testing/evidence/load-balancing.txt)) | **VERIFIED (live)** |
| R13 | **Inter-service communication** | booking ↔ room, booking ↔ payment, payment → booking, services → auth | `RoomServiceClient`, `PaymentServiceClient`, `OutboxRelay`, `ServiceTokenProvider`, `Retrier`, `HttpClients` | `/internal/v1/**` | `outbox_events`, `processed_events` | `DownstreamContractTest` (5), `OutboxRelayContractTest` (3), provider contract checks | e2e; correlation ID traced gateway → booking → room | **VERIFIED** |
| R14 | **Booking-to-payment communication** | booking → payment | `BookingOrchestrator` step 4, `PaymentServiceClient` | `POST /internal/v1/payments`, `/cancel`, `/refunds` | `payments.booking_id` (unique) | `happyPath…`, `outageLeavesBookingCreated…`, `refundsArePartialOrFull…` | e2e §4, §7 | **VERIFIED** |
| R15 | **Payment-to-room inventory workflow** | payment → (outbox) → booking → room | `OutboxService`, `OutboxRelay`, `PaymentEventService`, `InventoryService.confirm/release` | `/internal/v1/payment-events`, `/internal/v1/holds/{id}/confirm`, `/release` | `outbox_events`, `inventory_allocations` | `happyPath…`, `failedPaymentReleasesInventory`, `paymentSucceededButRoomLostIsCompensated…`, `transientFailureWhileConfirmingReturns503…` | e2e §6, §8, §9 (webhook) | **VERIFIED** |
| R16 | **Unit tests** | all | `*Test.java`, `frontend/**/*.test.ts(x)` | — | — | pricing, state machines, policy, signatures, retry, schemas, components | — | **VERIFIED (tests)** |
| R17 | **Integration tests** | all services | Testcontainers PostgreSQL 16 | — | real schemas | 7 integration classes (see TESTING.md) | — | **VERIFIED (tests)** |
| R18 | **Docker deployment configuration** | repo root | 6 service `Dockerfile`s, `frontend/Dockerfile`, `docker-compose.yml`, `init-env.sh`, `reset-data.sh` | — | volumes | CI job written (not yet run) | `docker compose config` valid; 7 images built; 8 containers healthy | **VERIFIED (local)** |
| R19 | **Cloud deployment** | — | [DEPLOYMENT.md](deployment/DEPLOYMENT.md#cloud-deployment) | — | — | — | none | **NOT IMPLEMENTED** (documented path; needs cloud account — EXTERNAL DEPENDENCY) |
| R20 | **High-traffic booking considerations** (concurrency, idempotency) | room, booking, payment | exclusion constraint, room row lock, idempotency keys, SKIP LOCKED jobs, virtual threads, timeouts | — | constraints listed in DATABASE.md | `concurrentOverlappingHoldsAllowExactlyOneWinner`, `concurrentSweepersExpireEachHoldExactlyOnce`, `concurrentDuplicateWebhooksAreAppliedOnce` | load test 1 119 req/s, 10-way contention → 1 winner; audit passed ([evidence](testing/evidence/)) | **VERIFIED** |
| R21 | Authentication service/module (real, not hard-coded tokens) | auth-service | as R1 | — | — | as R1 | — | **VERIFIED** |
| R22 | **Booking and payment coordination** (saga, compensation, recovery) | booking-service | `BookingOrchestrator`, `PaymentEventService`, `CancellationService`, `BookingRecoveryJob` | — | `bookings.status`, history | 15 saga tests + `BookingRecoverySchedulingTest` | e2e; live expiry of `GV-YAVVSA` | **VERIFIED** |

## 2. Detailed requirements from the implementation directive

| Requirement | Evidence | Status |
|---|---|---|
| Prevent double booking under concurrency (DB guarantee) | exclusion constraint `ex_allocations_no_overlap`; 8-thread test; live 10-way race (load test and audit) | **VERIFIED** |
| Temporary holds with expiry; expired inventory becomes available | `hold_expires_at`; `HoldExpiryJob`; `HoldExpirySchedulingIntegrationTest` (scheduled, restart-safe, exactly-once) | **VERIFIED** |
| Unpaid bookings reach a terminal state | `BookingRecoveryJob.expireUnpaid` → `EXPIRED`; `BookingRecoverySchedulingTest`; live `GV-YAVVSA` | **VERIFIED** |
| Late payment cannot confirm an expired booking; late success compensated | payment voided before expiry; `lateSuccessForAVoidedPaymentCannotConfirmIt`, `latePaymentSuccessForAnExpiredBookingIsRefundedNeverConfirmed`; re-acquire/refund tests | **VERIFIED (tests)** |
| Idempotent booking and payment requests | unique keys; `retriesWithSameKey…`, `successfulPaymentIsIdempotent…`; audit scenarios 2 and 6 | **VERIFIED** |
| Cancellation policy and refunds | `CancellationPolicy`, `BookingRulesTest` boundaries; `confirmedBookingCancellationRefundsPerPolicy`; e2e §7 | **VERIFIED** |
| Price snapshot stored and immutable | `booking_price_snapshots` (`@Immutable`, total CHECK); e2e "price snapshot stored" | **VERIFIED** |
| Client cannot set price or payment result | no amount/status fields accepted; `clientCannotSupplyAmountOrFakeSuccess…`; `PRICE_CHANGED` test | **VERIFIED (tests)** |
| Webhook verification, replay protection, de-duplication | `WebhookSignature`, `webhook_events`; tests incl. concurrent duplicates; e2e forged webhook 401 | **VERIFIED** |
| Timeouts, bounded retries, downstream failure handling | `HttpClients`, `Retrier`; real slow-server timeout test; outage test | **VERIFIED (tests)** |
| Correlation IDs and structured logs | `CorrelationIdFilter`, gateway filter, outbox; live trace across three services | **VERIFIED** |
| Health, readiness, metrics | actuator probes used by Docker; Prometheus endpoint used for the load-balancing measurement | **VERIFIED** |
| RFC 9457 errors, validation, OpenAPI/Swagger | `GlobalExceptionHandler`, tests asserting `code`; Swagger UI aggregated at the gateway (4 specs, 60 paths) | **VERIFIED** |
| Secure file upload, path traversal prevention | magic-byte detection, key pattern, canonical path; `MediaStorageTest`, upload test | **VERIFIED (tests)** |
| Rate limiting and brute-force protection | gateway token buckets; account lockout; tests for both | **VERIFIED** |
| Secrets not committed or printed | `.gitignore` + `git check-ignore`; `init-env.sh`; masked `toString()` | **VERIFIED** |
| Frontend pages (home, search, details, auth, checkout, confirmation, dashboard, admin, errors) | `frontend/src/pages`; browser walkthrough; 24 tests | **VERIFIED** |
| Authoritative confirmation (no optimistic "success") | `ConfirmationPage` polling; component tests; browser: "Waiting…" then "Confirmed" | **VERIFIED** |
| Responsive layout 375–1440 px | audit at 375/390/430/768/1024/1440 after fixes (TESTING.md §6) | **VERIFIED** (Chromium emulation, not physical devices) |
| Accessibility basics | semantic HTML, labels, focus styles, skip link, axe-core checks (no serious/critical) | **VERIFIED (tests)** (no full WCAG audit) |
| Charts with real data | admin overview (Recharts) from live endpoints, table view alternative | **VERIFIED** |
| CI pipeline | `.github/workflows/ci.yml` | **IMPLEMENTED, NOT VERIFIED** (never run on GitHub) |
| Dependency vulnerability checks | advisory `npm audit` + dependency review in CI | **IMPLEMENTED, NOT VERIFIED** |
| Real payment provider (sandbox) | `provider/razorpay/*` (`RazorpayPaymentProvider`, `RazorpayClient`, `RazorpayWebhookService`), `POST /payments/{id}/checkout`, frontend `lib/razorpay.ts`; tests `RazorpayFlowIntegrationTest` (8, fake Razorpay API), `RazorpaySignaturesTest` (3, openssl vectors), `payment.test.tsx` (3); start-up refusal without keys checked live | **PARTIAL** — implemented and tested against a fake API; not run against Razorpay's test API (**needs test keys from the owner**) |
| Message broker (RabbitMQ) | `platform-commons` `messaging/*`; payment `OutboxRelay` + `RabbitOutboxPublisher`; booking `BookingOutboxRelay`, `PaymentEventListener`; queues with DLQs; tests `RabbitOutboxPublisherIntegrationTest`, `BookingMessagingIntegrationTest` (real broker) | **VERIFIED** (tests + live e2e) |
| E-mail notifications | `notification-service` (`DomainEventListener`, `NotificationService`, Thymeleaf templates), Mailpit; tests `NotificationFlowIntegrationTest` (real RabbitMQ + Mailpit), `EmailComposerTest`; e2e section 6b and checks for cancellation/failed-payment e-mails | **VERIFIED** (tests + live e2e) |
| High availability: 2 Eureka peers, 2 gateways behind nginx, 2 booking replicas | `docker-compose.yml`, `infrastructure/docker/gateway-lb/nginx.conf`, discovery `EUREKA_PEER_URL`; `scripts/failover-demo.sh` ([evidence](testing/evidence/failover-demo.txt): run A 99/100 requests + 4/4 bookings, run B 100/100 + 4/4) | **VERIFIED (live)** |
| Resilience4j circuit breakers, retries, timeouts, fallbacks | gateway `CircuitBreaker` + connect-failure `Retry` per route, `FallbackController`; booking `Resilience`, `TransientFailure`; tests `GatewayIntegrationTest` (3 new), `DownstreamContractTest` (2 new) | **VERIFIED** (tests + live) |
| Automated test coverage report | JaCoCo per module + `coverage/` aggregate, `scripts/coverage-summary.sh` | **VERIFIED** — 86.6 % lines, 66.0 % branches (196 tests) |
| Metrics dashboards (optional) | `--profile observability`: Prometheus (DNS discovery of all instances) + Grafana dashboard `grandvista-overview` | **VERIFIED (live)** — 6/6 targets up, dashboard queries return data |
| Shared rate limiting across gateways | in-memory limiter per gateway instance (a client can reach 2× the limit) | **PARTIAL** |
| Separate database server per service | `docker-compose.yml` (`auth-db`, `room-db`, `booking-db`, `payment-db`), `infrastructure/docker/postgres/init/01-create-service-db.sh`; `scripts/verify-db-isolation.sh` (28/28 checks) | **VERIFIED (live)** |
