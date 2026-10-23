#!/usr/bin/env bash
# End-to-end smoke test against a RUNNING stack, exercising every user journey through the API gateway.
#   ./scripts/e2e-smoke.sh [base-url]        (default http://localhost:8080)
# Requires: curl, jq. Reads the bootstrap admin credentials from .env for the admin checks.
# Exit code is non-zero if any check fails.
set -uo pipefail
cd "$(dirname "$0")/.."

BASE="${1:-http://localhost:8080}"
# Mailpit captures the platform's e-mails locally; set MAILPIT_URL= (empty) to skip the e-mail checks.
MAILPIT="${MAILPIT_URL-http://localhost:8025}"
API="$BASE/api/v1"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
PASS=0
FAIL=0

green() { printf '\033[32m%s\033[0m\n' "$*"; }
red() { printf '\033[31m%s\033[0m\n' "$*"; }
check() { # check "description" expected actual
  if [[ "$2" == "$3" ]]; then PASS=$((PASS + 1)); green "  ✔ $1"; else FAIL=$((FAIL + 1)); red "  ✘ $1 (expected '$2', got '$3')"; fi
}
section() { printf '\n\033[1m%s\033[0m\n' "$*"; }

# http METHOD PATH [json-body] [token] [extra curl args...]  -> writes body to $WORK/body, echoes status
http() {
  local method="$1" path="$2" body="${3:-}" token="${4:-}"
  shift 4 2>/dev/null || shift $#
  local args=(-s -o "$WORK/body" -w '%{http_code}' -X "$method" "$API$path" -H 'Accept: application/json'
              -H 'X-Requested-With: XMLHttpRequest' -H "X-Correlation-Id: e2e-$(date +%s)-$RANDOM")
  [[ -n "$token" ]] && args+=(-H "Authorization: Bearer $token")
  [[ -n "$body" ]] && args+=(-H 'Content-Type: application/json' --data "$body")
  curl "${args[@]}" "$@"
}
body() { cat "$WORK/body"; }
field() { jq -r "$1" "$WORK/body"; }

date_plus() { # date_plus N -> yyyy-mm-dd, N days from today (hotel zone Asia/Kolkata)
  TZ=Asia/Kolkata date -v+"$1"d +%F 2>/dev/null || TZ=Asia/Kolkata date -d "+$1 days" +%F
}

register() { # register <cookie-jar> -> prints access token
  local email="e2e-$(date +%s)-$RANDOM@example.test"
  local status
  status=$(http POST /auth/register "{\"email\":\"$email\",\"password\":\"correct-horse-42\",\"fullName\":\"E2E Guest\"}" "" -c "$1")
  [[ "$status" == "201" ]] || { red "registration failed: $status $(body)"; return 1; }
  field .accessToken
}

poll_status() { # poll_status <bookingId> <token> <wanted> [seconds]
  local deadline=$((SECONDS + ${4:-40})) status=""
  while (( SECONDS < deadline )); do
    http GET "/bookings/$1/status" "" "$2" >/dev/null
    status=$(field .status)
    [[ "$status" == "$3" ]] && break
    sleep 1
  done
  echo "$status"
}

mail_count() { # mail_count <recipient> <subject> -> number of matching e-mails in Mailpit
  curl -s -G "$MAILPIT/api/v1/search" --data-urlencode "query=to:\"$1\" subject:\"$2\"" | jq -r '.messages_count // 0'
}
await_mail() { # await_mail <recipient> <subject> [seconds] -> prints the count once >= 1 (or 0 on timeout)
  local deadline=$((SECONDS + ${3:-30})) n=0
  while (( SECONDS < deadline )); do
    n=$(mail_count "$1" "$2")
    (( n >= 1 )) && break
    sleep 1
  done
  echo "$n"
}

section "0. Platform health"
# Containers report healthy before Eureka has propagated every instance to the gateway (up to ~30 s after a
# fresh start). Wait until each public route is served, so the checks below test behaviour, not start-up timing.
wait_ready() {
  local deadline=$((SECONDS + ${READY_TIMEOUT:-180})) path code
  # The per-service API-docs routes are public and reach each service through Eureka.
  for path in auth-service room-service booking-service payment-service notification-service; do
    while :; do
      code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api-docs/$path")
      [[ "$code" != "503" && "$code" != "000" && "$code" != "502" && "$code" != "504" ]] && break
      (( SECONDS > deadline )) && { red "  services not routable after ${READY_TIMEOUT:-180}s ($path → $code)"; break; }
      sleep 2
    done
  done
}
wait_ready
check "gateway is healthy" "UP" "$(curl -s "$BASE/actuator/health" | jq -r .status)"
check "unknown internal path is not exposed" "404" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/internal/v1/holds")"

section "1. Room search and availability"
OFFSET=$((40 + RANDOM % 250))
CHECK_IN=$(date_plus "$OFFSET")
CHECK_OUT=$(date_plus $((OFFSET + 2)))
check "search with dates succeeds" "200" "$(http GET "/rooms?checkIn=$CHECK_IN&checkOut=$CHECK_OUT&guests=2&size=50" "")"
ROOM_ID=$(field '.content[0].id')
ROOM_PROPERTY=$(field '.content[0].property.id')
AVAILABLE_BEFORE=$(field '[.content[].id] | length')
echo "  stay $CHECK_IN → $CHECK_OUT, room $ROOM_ID ($AVAILABLE_BEFORE rooms available)"
check "results carry a dynamic stay price" "2" "$(field '.content[0].stayPrice.nights')"
check "invalid range is rejected" "400" "$(http GET "/rooms?checkIn=$CHECK_OUT&checkOut=$CHECK_IN" "")"
check "invalid range has problem code" "INVALID_DATE_RANGE" "$(field .code)"

section "1b. Hotel portfolio (multi-property)"
check "hotel list is public" "200" "$(http GET /properties "")"
check "  ... three demo hotels" "3" "$(field 'length')"
check "  ... in three cities" "3" "$(field '[.[].city] | unique | length')"
DXB_ID=$(field '.[] | select(.code == "DXB") | .id')
check "search by city returns only that city" "true" "$(http GET "/rooms?city=Goa&checkIn=$CHECK_IN&checkOut=$CHECK_OUT&size=50" "" >/dev/null; jq '(.content | length) > 0 and all(.content[]; .property.city == "Goa")' "$WORK/body")"
check "search by hotel returns only that hotel" "true" "$(http GET "/rooms?propertyId=$DXB_ID&size=50" "" >/dev/null; jq '(.content | length) > 0 and all(.content[]; .property.code == "DXB")' "$WORK/body")"
check "  ... priced in the hotel's own time zone" "Asia/Dubai" "$(field '.content[0].property.timeZone')"

section "2. Registration, login and session refresh"
JAR_A="$WORK/a.cookies"
TOKEN_A=$(register "$JAR_A") || exit 1
check "customer token issued" "true" "$([[ -n "$TOKEN_A" ]] && echo true || echo false)"
check "profile is protected and readable" "200" "$(http GET /auth/me "" "$TOKEN_A")"
check "refresh cookie rotates session" "200" "$(http POST /auth/refresh "" "" -b "$JAR_A" -c "$JAR_A")"
TOKEN_A=$(field .accessToken)
check "unauthenticated booking list is rejected" "401" "$(http GET /bookings/my "")"
check "customer cannot use admin API" "403" "$(http GET /admin/bookings "" "$TOKEN_A")"

section "3. Dynamic pricing quote"
check "quote succeeds" "200" "$(http GET "/rooms/$ROOM_ID/quote?checkIn=$CHECK_IN&checkOut=$CHECK_OUT&guests=2" "")"
TOTAL=$(field .quote.total)
check "quote has one rate per night" "2" "$(field '.quote.nightlyRates | length')"
check "total = subtotal + fee + taxes" "true" "$(jq '(((.quote.subtotal + .quote.serviceFee + .quote.taxes) * 100) | round) == ((.quote.total * 100) | round)' "$WORK/body")"

section "4. Booking with idempotency and inventory hold"
KEY="e2e-$(date +%s)-$RANDOM"
BODY_NO_TOTAL="{\"roomId\":\"$ROOM_ID\",\"checkIn\":\"$CHECK_IN\",\"checkOut\":\"$CHECK_OUT\",\"guests\":2,\"primaryGuest\":{\"fullName\":\"E2E Guest\",\"email\":\"guest@example.test\"}}"
BOOKING_BODY="${BODY_NO_TOTAL%\}},\"expectedTotal\":$TOTAL}"
check "booking created" "201" "$(http POST /bookings "$BOOKING_BODY" "$TOKEN_A" -H "Idempotency-Key: $KEY")"
BOOKING_ID=$(field .id)
PAYMENT_ID=$(field .paymentId)
REF_A=$(field .reference)
check "booking awaits payment" "PENDING_PAYMENT" "$(field .status)"
check "price snapshot stored" "$TOTAL" "$(field .price.total)"
check "booking records its hotel" "$ROOM_PROPERTY" "$(field .room.propertyId)"
check "retry with same key returns the same booking" "200" "$(http POST /bookings "$BOOKING_BODY" "$TOKEN_A" -H "Idempotency-Key: $KEY")"
check "  ... same id" "$BOOKING_ID" "$(field .id)"
http GET "/rooms?checkIn=$CHECK_IN&checkOut=$CHECK_OUT&guests=2&size=50" "" >/dev/null
check "held room disappears from availability" "false" "$(jq --arg id "$ROOM_ID" '[.content[].id] | index($id) != null' "$WORK/body")"

section "5. Contention: another customer cannot take the held room"
JAR_B="$WORK/b.cookies"
TOKEN_B=$(register "$JAR_B") || exit 1
check "overlapping booking is refused" "409" "$(http POST /bookings "$BODY_NO_TOTAL" "$TOKEN_B" -H "Idempotency-Key: e2e-b-$RANDOM$RANDOM")"
check "  ... with ROOM_UNAVAILABLE" "ROOM_UNAVAILABLE" "$(field .code)"
check "other customer cannot read the booking" "404" "$(http GET "/bookings/$BOOKING_ID" "" "$TOKEN_B")"
check "other customer cannot pay the booking" "404" "$(http POST "/payments/$PAYMENT_ID/confirm" '{"paymentMethodToken":"sim_card_success"}' "$TOKEN_B" -H "Idempotency-Key: e2e-pay-$RANDOM$RANDOM")"

section "6. Simulated payment and authoritative confirmation"
PAY_KEY="e2e-pay-$RANDOM$RANDOM"
check "payment succeeds" "200" "$(http POST "/payments/$PAYMENT_ID/confirm" '{"paymentMethodToken":"sim_card_success"}' "$TOKEN_A" -H "Idempotency-Key: $PAY_KEY")"
check "  ... status SUCCEEDED" "SUCCEEDED" "$(field .status)"
check "  ... marked simulated" "true" "$(field .simulated)"
check "payment retry is idempotent" "SUCCEEDED" "$(http POST "/payments/$PAYMENT_ID/confirm" '{"paymentMethodToken":"sim_card_success"}' "$TOKEN_A" -H "Idempotency-Key: $PAY_KEY" >/dev/null; field .status)"
check "booking becomes CONFIRMED (via outbox event)" "CONFIRMED" "$(poll_status "$BOOKING_ID" "$TOKEN_A" CONFIRMED)"
http GET /bookings/my "" "$TOKEN_A" >/dev/null
check "booking appears in customer history" "true" "$(jq --arg id "$BOOKING_ID" '[.content[].id] | index($id) != null' "$WORK/body")"

if [[ -n "$MAILPIT" ]]; then
  section "6b. E-mail notifications (RabbitMQ → notification-service → Mailpit)"
  EMAIL_A=$(http GET /auth/me "" "$TOKEN_A" >/dev/null; field .email)
  check "confirmation e-mail delivered" "1" "$(await_mail "$EMAIL_A" "Your stay is confirmed — $REF_A")"
  check "payment receipt e-mail delivered" "1" "$(await_mail "$EMAIL_A" "Payment receipt — $REF_A")"
fi

section "7. Cancellation, refund and inventory release"
http GET "/bookings/$BOOKING_ID/cancellation-quote" "" "$TOKEN_A" >/dev/null
check "far-future stay gets a full refund quote" "100" "$(field .refundPercent)"
check "cancellation completes" "200" "$(http POST "/bookings/$BOOKING_ID/cancel" '{"reason":"e2e"}' "$TOKEN_A")"
check "  ... status CANCELLED" "CANCELLED" "$(field .status)"
check "  ... refund equals total" "$TOTAL" "$(field .refundAmount)"
http GET "/payments/by-booking/$BOOKING_ID" "" "$TOKEN_A" >/dev/null
check "payment is REFUNDED" "REFUNDED" "$(field .status)"
http GET "/rooms/$ROOM_ID/availability?checkIn=$CHECK_IN&checkOut=$CHECK_OUT" "" >/dev/null
check "room is available again" "true" "$(field .available)"
if [[ -n "$MAILPIT" ]]; then
  check "cancellation e-mail delivered (once)" "1" "$(await_mail "$EMAIL_A" "Your booking has been cancelled — $REF_A")"
fi

section "8. Failed payment releases the hold"
KEY2="e2e-fail-$RANDOM$RANDOM"
check "second booking created" "201" "$(http POST /bookings "$BODY_NO_TOTAL" "$TOKEN_B" -H "Idempotency-Key: $KEY2")"
B2=$(field .id); P2=$(field .paymentId)
check "declined card fails" "FAILED" "$(http POST "/payments/$P2/confirm" '{"paymentMethodToken":"sim_card_declined"}' "$TOKEN_B" -H "Idempotency-Key: e2e-d-$RANDOM$RANDOM" >/dev/null; field .status)"
check "booking becomes PAYMENT_FAILED" "PAYMENT_FAILED" "$(poll_status "$B2" "$TOKEN_B" PAYMENT_FAILED)"
REF_B2=$(http GET "/bookings/$B2" "" "$TOKEN_B" >/dev/null; field .reference)
http GET "/rooms/$ROOM_ID/availability?checkIn=$CHECK_IN&checkOut=$CHECK_OUT" "" >/dev/null
check "room released after failure" "true" "$(field .available)"
if [[ -n "$MAILPIT" ]]; then
  EMAIL_B=$(http GET /auth/me "" "$TOKEN_B" >/dev/null; field .email)
  check "payment-failed e-mail delivered" "1" "$(await_mail "$EMAIL_B" "Your payment was not completed — $REF_B2")"
fi

section "9. Asynchronous payment confirmed by signed webhook"
KEY3="e2e-async-$RANDOM$RANDOM"
check "third booking created" "201" "$(http POST /bookings "$BODY_NO_TOTAL" "$TOKEN_B" -H "Idempotency-Key: $KEY3")"
B3=$(field .id); P3=$(field .paymentId)
check "async payment starts PENDING" "PENDING" "$(http POST "/payments/$P3/confirm" '{"paymentMethodToken":"sim_card_async_success"}' "$TOKEN_B" -H "Idempotency-Key: e2e-a-$RANDOM$RANDOM" >/dev/null; field .status)"
check "booking confirmed after the webhook" "CONFIRMED" "$(poll_status "$B3" "$TOKEN_B" CONFIRMED 45)"
check "forged webhook is rejected" "401" "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/payments/webhooks/simulated" -H 'Content-Type: application/json' -H 'X-Provider-Signature: t=1,v1=00' --data '{"id":"evt_forged","type":"charge.succeeded","data":{"reference":"x"}}')"

section "10. Administration"
ADMIN_EMAIL=$(grep -E '^GV_BOOTSTRAP_ADMIN_EMAIL=' .env 2>/dev/null | cut -d= -f2-)
ADMIN_PASSWORD=$(grep -E '^GV_BOOTSTRAP_ADMIN_PASSWORD=' .env 2>/dev/null | cut -d= -f2-)
if [[ -n "$ADMIN_EMAIL" && -n "$ADMIN_PASSWORD" ]]; then
  check "administrator can sign in" "200" "$(http POST /auth/login "$(jq -n --arg e "$ADMIN_EMAIL" --arg p "$ADMIN_PASSWORD" '{email:$e,password:$p}')" "")"
  ADMIN=$(field .accessToken)
  check "inventory summary" "200" "$(http GET /admin/inventory/summary "" "$ADMIN")"
  check "booking summary" "200" "$(http GET /admin/bookings/summary "" "$ADMIN")"
  check "payment summary" "200" "$(http GET /admin/payments/summary "" "$ADMIN")"
  OTHER_ROOM=$(http GET "/rooms?checkIn=$CHECK_IN&checkOut=$CHECK_OUT&size=50" "" >/dev/null; jq -r --arg id "$ROOM_ID" '[.content[].id | select(. != $id)][0]' "$WORK/body")
  check "room put into maintenance" "MAINTENANCE" "$(http PUT "/admin/rooms/$OTHER_ROOM/status" '{"status":"MAINTENANCE","reason":"e2e check"}' "$ADMIN" >/dev/null; field .room.status)"
  http GET "/rooms?checkIn=$CHECK_IN&checkOut=$CHECK_OUT&size=50" "" >/dev/null
  check "maintenance room not offered" "false" "$(jq --arg id "$OTHER_ROOM" '[.content[].id] | index($id) != null' "$WORK/body")"
  check "room back to active" "ACTIVE" "$(http PUT "/admin/rooms/$OTHER_ROOM/status" '{"status":"ACTIVE"}' "$ADMIN" >/dev/null; field .room.status)"
  check "nobody can edit payment outcomes" "403" "$(http PUT "/admin/payments/$P2" '{"status":"SUCCEEDED"}' "$ADMIN")"
  check "bookings can be filtered by hotel" "true" "$(http GET "/admin/bookings?propertyId=$ROOM_PROPERTY&size=100" "" "$ADMIN" >/dev/null; jq --arg p "$ROOM_PROPERTY" '(.content | length) > 0 and all(.content[]; .room.propertyId == $p)' "$WORK/body")"

  section "11. Stock reconciliation (room allocations vs bookings)"
  check "customer cannot run a reconciliation" "403" "$(http POST "/admin/inventory/reconciliation/run?propertyId=$ROOM_PROPERTY" "" "$TOKEN_A")"
  check "reconciliation runs for the booked hotel" "200" "$(http POST "/admin/inventory/reconciliation/run?propertyId=$ROOM_PROPERTY" "" "$ADMIN")"
  RUN_ID=$(field .id)
  check "  ... completes (booking-service reached with a service token)" "COMPLETED" "$(field .status)"
  check "  ... checked this run's allocations" "true" "$(jq '.allocationsChecked >= 0 and (.findingCount == (.findings | length))' "$WORK/body")"
  echo "  report: $(jq -c '{allocationsChecked, bookingsChecked, findingCount, releasedCount}' "$WORK/body")"
  check "latest report is the run just made" "$RUN_ID" "$(http GET "/admin/inventory/reconciliation?propertyId=$ROOM_PROPERTY" "" "$ADMIN" >/dev/null; field .id)"
  check "run history lists it" "true" "$(http GET "/admin/inventory/reconciliation/runs?propertyId=$ROOM_PROPERTY" "" "$ADMIN" >/dev/null; jq --arg id "$RUN_ID" '[.[].id] | index($id) != null' "$WORK/body")"
else
  red "  (skipped: GV_BOOTSTRAP_ADMIN_EMAIL/PASSWORD not found in .env)"
fi

section "Result"
echo "  passed: $PASS   failed: $FAIL"
(( FAIL == 0 )) && green "END-TO-END SMOKE TEST PASSED" || red "END-TO-END SMOKE TEST FAILED"
exit $(( FAIL == 0 ? 0 : 1 ))
