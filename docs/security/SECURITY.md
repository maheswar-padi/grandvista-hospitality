# Security review

Scope: the implementation in this repository as of 22 October 2026. Each control lists where it lives and how it
is verified. Residual risks are listed honestly at the end.

## 1. Assets and threat model

| Asset | Threats considered |
|---|---|
| Customer accounts and sessions | credential stuffing / brute force, token theft (XSS), CSRF on cookie endpoints, session replay, privilege escalation via registration |
| Bookings and personal data (names, e-mails, phones) | IDOR (guessing another booking's id), over-broad admin access, leakage via errors or logs |
| Inventory integrity | double booking under concurrency, manipulation of status/price via mass assignment, replayed requests |
| Payments | client-supplied amount or "success", forged/replayed provider callbacks, double charging on retries, staff editing outcomes |
| Internal APIs | direct calls to `/internal/**`, service impersonation |
| Platform secrets | signing keys, database and client secrets committed or logged |
| Uploaded files | malicious content (SVG/HTML), oversized uploads, path traversal |

STRIDE summary: **Spoofing** (JWT + JWKS, client credentials, webhook HMAC), **Tampering** (server-side pricing,
DTO allow-lists, DB constraints, signed tokens), **Repudiation** (audit tables with actor and correlation ID),
**Information disclosure** (404 for foreign resources, problem details without internals, no secrets in logs),
**Denial of service** (rate limits, size limits, timeouts, bounded retries), **Elevation of privilege** (roles only
from the issuer, backend enforcement on every service, no public admin creation).

## 2. Authentication

| Control | Implementation | Verified by |
|---|---|---|
| Password storage | BCrypt cost 12 behind `DelegatingPasswordEncoder` (upgradeable) | `AuthFlowIntegrationTest` |
| Password policy | 10–128 chars, at least one letter and one digit (server + client) | integration + frontend tests |
| Brute force | per-account lockout after 5 failures for 15 min; gateway limit 10 credential requests/min/IP; constant-ish timing for unknown e-mails (dummy hash check); identical error for unknown e-mail and wrong password | `loginFailureIsGenericAndAccountLocksAfterRepeatedFailures`, `GatewayIntegrationTest.credentialEndpointsAreRateLimited` |
| Access token | RS256 JWT, 15 min; claims `iss=grandvista-auth`, `aud=grandvista-api`, `sub`, `roles`, `jti`, `exp/nbf/iat` | `registerIssuesCustomerTokenAndHardenedRefreshCookie` |
| Token validation | signature via JWKS, issuer, audience, expiry, not-before — at the gateway **and** in every service | `protectedRoutesNeedAValidTokenWhichIsForwarded` (expired, wrong audience, tampered) |
| Signing key | private key only in the auth-service (PEM from a git-ignored file; ephemeral only in development; `prod` refuses to start without it); public key via `/oauth2/jwks` (only `n`, `e` — test asserts no `d`) | `jwksPublishesOnlyThePublicKey` |
| Refresh token | 256-bit random, stored as SHA-256, 7 days, rotated on every use; replay of a rotated token revokes the whole family | `refreshRotatesTokenAndReuseRevokesTheWholeFamily` |
| Logout | revokes the refresh-token family and clears the cookie; access tokens expire within 15 min | `logoutRevokesRefreshTokenAndClearsCookie` |
| Service identity | client-credentials grant (secrets ≥ 32 chars, constant-time comparison) → 5-min `SERVICE` token with audience `grandvista-internal` | `serviceTokensRequireValidClientCredentials`, `ServiceClientAuthenticatorTest` |
| Administrator bootstrap | created once from environment variables only when no admin exists; no default password; registration can't set roles | `bootstrapAdminCanManageRolesButNotDemoteSelf`, `registrationCannotEscalateRoleAndRejectsWeakPasswords` |

### Token storage in the browser — decision and trade-offs

| Option | XSS exposure | CSRF exposure | Decision |
|---|---|---|---|
| Access + refresh token in `localStorage` | any injected script can read both (long-lived theft) | none | rejected |
| Session cookie only | not readable by script | every endpoint needs CSRF tokens | rejected (stateless services) |
| **Access token in memory, refresh token in HttpOnly cookie** | script can't read the refresh token; an XSS could only use the 15-min access token while the page is open | only the two cookie endpoints are exposed; mitigated by `SameSite=Strict`, `Path=/api/v1/auth` and a mandatory `X-Requested-With` header (cross-origin pages can't add it without passing the CORS allow-list) | **chosen** |

The browser keeps only a non-secret flag (`gv.hasSession = 1`) in `localStorage` to avoid useless refresh calls.
Verified in a real browser: `document.cookie` does not contain `gv_refresh`; a reload restores the session.

## 3. Authorisation

Enforced by the **owning service** (the gateway adds a coarse first check). Ownership failures return **404** so
identifiers can't be probed.

| Capability | CUSTOMER | STAFF | ADMIN | SERVICE |
|---|---|---|---|---|
| Browse rooms, quotes, availability | ✔ (also anonymous) | ✔ | ✔ | — |
| Create a booking | ✔ | ✘ | ✘ | — |
| Read/cancel own bookings and payments | ✔ (own only) | ✔ all | ✔ all | — |
| Pay a booking | ✔ (own only) | ✘ | ✘ | — |
| Admin read views (inventory, bookings, payments, pricing) | ✘ | ✔ | ✔ | — |
| Change room operational status | ✘ | ✔ | ✔ | — |
| Create/edit rooms, images, room types, amenities, pricing policies | ✘ | ✘ | ✔ | — |
| Manage users and roles | ✘ | ✘ | ✔ (not own admin role) | — |
| Change a payment's outcome | ✘ | ✘ | ✘ (all writes under `/admin/payments` denied) | via provider only |
| `/internal/**` (holds, payments, events) | ✘ | ✘ | ✘ | ✔ |

Verified by: `authorizationIsEnforcedPerEndpoint` (room), `roleBoundariesAreEnforced` (payment),
`accessRulesAndValidation`, `bookingHistoryIsPrivatePaginatedAndShowsFinalStatuses` (booking),
`adminPathsRequirePersonnelRoles`, `internalAndActuatorPathsAreNeverExposed` (gateway), e2e checks 2, 5 and 10.

## 4. Input, output and data integrity

* **Validation** with Bean Validation on every DTO and parameter; structured `VALIDATION_FAILED` errors.
* **No mass assignment:** request DTOs list only writable fields; status, price, owner, roles and amounts are never
  taken from clients (extra JSON fields are ignored — tested by sending `amount` and `status` to the payment API).
* **Server-authoritative money:** the hold prices the stay; the booking compares it with `expectedTotal`; the
  payment is created by the booking-service with the snapshot amount; the browser never computes prices.
* **SQL injection:** JPA/Criteria and named JDBC parameters only; dynamic sort uses an enum whitelist.
* **Concurrency/replay:** idempotency keys (bookings, payment attempts, refunds, provider charges), unique
  constraints, row locks, exclusion constraint — see [ARCHITECTURE.md §7](../architecture/ARCHITECTURE.md#7-inventory-consistency).
* **Uploads:** type detected from magic bytes (JPEG/PNG/WebP only; SVG refused), 5 MB limit (also at the gateway and
  multipart config), max 10 per room, server-generated `uuid.ext` keys, strict key pattern + canonical-path check
  against traversal, served with `nosniff`, `Content-Security-Policy: default-src 'none'`.
* **Errors:** RFC 9457 documents with safe messages; `include-stacktrace: never`; unexpected errors are logged
  server-side with the correlation ID only.
* **Webhooks:** HMAC-SHA256 over the raw body with a timestamp (±5 min tolerance) and constant-time comparison;
  de-duplicated by provider event id; unknown payments answered 503 so the provider retries instead of losing data.
* **Redirects:** post-login `next` accepts only same-site relative paths (`safeRedirect`, unit-tested against
  `https://…`, `//…`, `/\…`, `javascript:`).
* **Frontend:** React escaping only; ESLint forbids `dangerouslySetInnerHTML`.

## 5. Transport, browser and platform hardening

* nginx: `Content-Security-Policy` (`script-src 'self'`, `frame-ancestors 'none'`, `object-src 'none'`),
  `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy`, `Permissions-Policy`,
  `server_tokens off`, 6 MB body limit.
* Gateway: CORS allow-list with credentials, request-size limit, per-IP rate limits, 2 s connect / 15 s response
  timeouts, only explicit routes (no discovery-locator), internal and actuator paths return 404.
* Services: stateless, CSRF disabled (bearer tokens), `denyAll` default rule, actuator exposes only health, info,
  prometheus.
* Containers run as non-root users with memory limits; database and Eureka ports bound to `127.0.0.1`; services
  not published at all; database credentials are per-service owners (least privilege between services).
* Eureka dashboard and API require HTTP Basic (`registryRequiresCredentials` test).

## 6. Secrets management

* `.env` and `infrastructure/secrets/*` are git-ignored (`git check-ignore` verified); `init-env.sh` generates
  random values with `openssl rand`, writes `.env` with mode 600 and never prints secrets.
* Configuration records and `toString()` of secret-bearing records mask secrets (`clientSecret=***`).
* Logs never contain passwords, tokens or secrets; the bootstrap admin e-mail is masked (`a***@domain`).
* Production: use a secret manager (see [DEPLOYMENT.md](../deployment/DEPLOYMENT.md#cloud-deployment)).

## 7. Residual risks and limitations

| Risk | Severity (demo context) | Notes / mitigation path |
|---|---|---|
| Access tokens remain valid up to 15 min after logout or role change | Low | short TTL; add a `jti` deny-list or shorter TTL if needed |
| Gateway trusts the right-most `X-Forwarded-For` (`GV_TRUST_FORWARDED_FOR=true` in Compose); port 8080 is also published, so a client calling the gateway directly can choose its rate-limit key | Medium | in production only the reverse proxy may reach the gateway (private network) |
| In-memory rate limiting is per gateway instance | Low | Redis-backed limiter for several instances |
| Account lockout can be triggered by others (targeted lock-out) | Low | lockout is temporary (15 min); add CAPTCHA/risk-based checks in production |
| Registration reveals whether an e-mail is registered (409) | Low | common trade-off for usability; could send an e-mail instead |
| Swagger UI and OpenAPI documents are public in development | Low | disabled by `prod` profile / `API_DOCS_ENABLED=false` |
| `/actuator/prometheus` is unauthenticated on the internal network | Low | not routed publicly; restrict by network policy |
| CSP allows inline styles (`style-src 'unsafe-inline'`, needed by the charting library) | Low | scripts remain same-origin only |
| Uploaded images are stored as uploaded (metadata such as EXIF is not stripped) | Low | re-encode images in the media pipeline |
| Payment provider is simulated; real provider signature scheme not implemented | n/a | implement `PaymentProvider` + provider webhook verification |
| Dependency scanning is advisory (`npm audit`, dependency review) and not yet run in CI | Medium | enable OWASP dependency-check / Dependabot after pushing to GitHub |
| No TLS in local Compose | n/a locally | terminate HTTPS at the load balancer in any shared deployment |
| Single PostgreSQL server for four databases | Low | separate instances in production |
